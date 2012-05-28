package stormComponents;

import backtype.storm.Config;
import thetajoin.indexes.Index;

import java.util.ArrayList;
import java.util.Map;

import utilities.MyUtilities;

import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.InputDeclarer;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;
import expressions.ValueExpression;
import java.util.List;
import java.util.concurrent.Semaphore;


import thetajoin.matrixMapping.Matrix;
import thetajoin.matrixMapping.OptimalPartition;
import operators.AggregateOperator;
import operators.ChainOperator;
import operators.DistinctOperator;
import operators.Operator;
import operators.ProjectionOperator;
import operators.SelectionOperator;
import utilities.SystemParameters;
import storage.TupleStorage;

import org.apache.log4j.Logger;

import thetajoin.predicateAnalyser.PredicateAnalyser;
import predicates.ComparisonPredicate;
import predicates.Predicate;
import stormComponents.synchronization.TopologyKiller;
import utilities.PeriodicBatchSend;
import visitors.PredicateCreateIndexesVisitor;
import visitors.PredicateUpdateIndexesVisitor;

public class StormThetaJoin extends BaseRichBolt implements StormJoin, StormComponent {
	private static final long serialVersionUID = 1L;
	private static Logger LOG = Logger.getLogger(StormThetaJoin.class);

	private int _hierarchyPosition=INTERMEDIATE;

	private StormEmitter _firstEmitter, _secondEmitter;
	private TupleStorage _firstRelationStorage, _secondRelationStorage;
	
	private String _componentName;
	private int _ID;

	private int _numSentTuples=0;
	private boolean _printOut;

	private ChainOperator _operatorChain;
	private OutputCollector _collector;
	private Map _conf;

	//position to test for equality in first and second emitter
	//join params of current storage then other relation interchangably !!
	List<Integer> _joinParams;

	//output has hash formed out of these indexes
	private List<Integer> _hashIndexes;
	private List<ValueExpression> _hashExpressions;
	
	private Predicate _joinPredicate;
	private OptimalPartition _partitioning;
	
	private List<Index> _firstRelationIndexes, _secondRelationIndexes;
	private List<Integer> _operatorForIndexes;
	private List<Object> _typeOfValueIndexed;
	
	private List<Object> _listCoefA;
	private List<Object> _listCoefB;
	
	private List<Integer> _listColRef = new ArrayList<Integer>();

	
	
	private boolean _existIndexes = false;

	//for No ACK: the total number of tasks of all the parent compoonents
	private int _numRemainingParents;

	//for batch sending
	private final Semaphore _semAgg = new Semaphore(1, true);
	private boolean _firstTime = true;
	private PeriodicBatchSend _periodicBatch;
	private long _batchOutputMillis;

	public StormThetaJoin(StormEmitter firstEmitter,
			StormEmitter secondEmitter,
			String componentName,
			SelectionOperator selection,
			DistinctOperator distinct,
			ProjectionOperator projection,
			AggregateOperator aggregation,
			List<Integer> hashIndexes,
			List<ValueExpression> hashExpressions,
			Predicate joinPredicate,
			int hierarchyPosition,
			boolean printOut,
			long batchOutputMillis,
			TopologyBuilder builder,
			TopologyKiller killer,
			Config conf) {
		_conf = conf;
		_firstEmitter = firstEmitter;
		_secondEmitter = secondEmitter;
		_componentName = componentName;
		_batchOutputMillis = batchOutputMillis;
		
		int firstCardinality=SystemParameters.getInt(conf, firstEmitter.getName()+"_CARD");
		int secondCardinality=SystemParameters.getInt(conf, secondEmitter.getName()+"_CARD");

		int parallelism = SystemParameters.getInt(conf, _componentName+"_PAR");

		//            if(parallelism > 1 && distinct != null){
		//                throw new RuntimeException(_componentName + ": Distinct operator cannot be specified for multiThreaded bolts!");
		//            }

		_operatorChain = new ChainOperator(selection, distinct, projection, aggregation);

		_hashIndexes = hashIndexes;
		_hashExpressions = hashExpressions;
		_joinPredicate = joinPredicate;

		_hierarchyPosition = hierarchyPosition;

		_ID=MyUtilities.getNextTopologyId();
		InputDeclarer currentBolt = builder.setBolt(Integer.toString(_ID), this, parallelism);
		
		Matrix makides = new Matrix(firstCardinality, secondCardinality);
		_partitioning = new OptimalPartition (makides, parallelism);
		
		currentBolt = MyUtilities.thetaAttachEmitterComponents(currentBolt, firstEmitter, secondEmitter,_partitioning,conf);
		
		if( _hierarchyPosition == FINAL_COMPONENT && (!MyUtilities.isAckEveryTuple(conf))){
			killer.registerComponent(this, parallelism);
		}

		_printOut= printOut;
		if (_printOut && _operatorChain.isBlocking()){
			currentBolt.allGrouping(Integer.toString(killer.getID()), SystemParameters.DUMP_RESULTS_STREAM);
		}

		_firstRelationStorage = new TupleStorage();
		_secondRelationStorage = new TupleStorage();


		
		PredicateAnalyser predicateAnalyser = new PredicateAnalyser();
		
		Predicate modifiedPredicate = predicateAnalyser.analyse(_joinPredicate);
		System.out.println("--"+modifiedPredicate);
		
		if (modifiedPredicate == _joinPredicate) { //cannot create index
			_existIndexes = false;
		} else {
			System.out.println("index-----------------");
			_joinPredicate = modifiedPredicate;
			createIndexes();
			_existIndexes = true;
		}

	}
	
	private void createIndexes(){
		PredicateCreateIndexesVisitor visitor = new PredicateCreateIndexesVisitor();
		
		_joinPredicate.accept(visitor);
		
		_firstRelationIndexes = new ArrayList<Index>(visitor._firstRelationIndexes);
		_secondRelationIndexes = new ArrayList<Index>(visitor._secondRelationIndexes);
		_operatorForIndexes = new ArrayList<Integer>(visitor._operatorForIndexes);
		_typeOfValueIndexed = new ArrayList<Object>(visitor._typeOfValueIndexed);
		
		_listCoefA = new ArrayList<Object>(visitor._coefA);
		_listCoefB = new ArrayList<Object>(visitor._coefB);
		
		_listColRef = new ArrayList<Integer>(visitor._colsRef);
	}
	

	@Override
	public void execute(Tuple stormTupleRcv) {
		if(_firstTime && MyUtilities.isBatchOutputMode(_batchOutputMillis)){
			_periodicBatch = new PeriodicBatchSend(_batchOutputMillis, this);
			_firstTime = false;
		}

		if (receivedDumpSignal(stormTupleRcv)) {
			MyUtilities.dumpSignal(this, stormTupleRcv, _collector);
			return;
		}

		String inputComponentName=stormTupleRcv.getString(0);
		String inputTupleString=stormTupleRcv.getString(1);   //INPUT TUPLE
		String inputTupleHash=stormTupleRcv.getString(2);

		if(MyUtilities.isFinalAck(inputTupleString, _conf)){
			_numRemainingParents--;
			MyUtilities.processFinalAck(_numRemainingParents, _hierarchyPosition, stormTupleRcv, _collector, _periodicBatch);
			return;
		}

		String firstEmitterName = _firstEmitter.getName();
		String secondEmitterName = _secondEmitter.getName();

		boolean isFromFirstEmitter = false;
		
		TupleStorage affectedStorage, oppositeStorage;
		List<Index> affectedIndexes, oppositeIndexes;
		
		if(firstEmitterName.equals(inputComponentName)){
			//R update
			isFromFirstEmitter = true;
			affectedStorage = _firstRelationStorage;
			oppositeStorage = _secondRelationStorage;
			affectedIndexes = _firstRelationIndexes;
			oppositeIndexes = _secondRelationIndexes;
		}else if(secondEmitterName.equals(inputComponentName)){
			//S update
			isFromFirstEmitter = false;
			affectedStorage = _secondRelationStorage;
			oppositeStorage = _firstRelationStorage;
			affectedIndexes = _secondRelationIndexes;
			oppositeIndexes = _firstRelationIndexes;
		}else{
			throw new RuntimeException("InputComponentName " + inputComponentName +
					" doesn't match neither " + firstEmitterName + " nor " + secondEmitterName + ".");
		}

		//add the stormTuple to the specific storage
		long row_id = affectedStorage.insert(inputTupleString);

		List<String> valuesToApplyOnIndex = null;
		
		if(_existIndexes){
			valuesToApplyOnIndex = updateIndexes(stormTupleRcv, affectedIndexes, row_id);
		}

		performJoin( stormTupleRcv,
				inputTupleString, 
				inputTupleHash,
				isFromFirstEmitter,
				oppositeIndexes,
				valuesToApplyOnIndex,
				oppositeStorage);

		_collector.ack(stormTupleRcv);
	}
	
	private List<String> updateIndexes(Tuple stormTupleRcv, List<Index> affectedIndexes, long row_id){

		String inputComponentName = stormTupleRcv.getString(0); // Table name
		String inputTupleString = stormTupleRcv.getString(1); //INPUT TUPLE
		// Get a list of tuple attributes and the key value
		List<String> tuple = MyUtilities.stringToTuple(inputTupleString, _conf);
		
		String firstEmitterName = _firstEmitter.getName();
		String secondEmitterName = _secondEmitter.getName();
		
		boolean comeFromFirstEmitter;
		
		if(inputComponentName.equals(firstEmitterName)){
			comeFromFirstEmitter = true;
		}else{
			comeFromFirstEmitter = false;
		}

		PredicateUpdateIndexesVisitor visitor = new PredicateUpdateIndexesVisitor(comeFromFirstEmitter, tuple);
		_joinPredicate.accept(visitor);

		List<String> valuesToIndex = new ArrayList<String>(visitor._valuesToIndex);
		List<Object> typesOfValuesToIndex = new ArrayList<Object>(visitor._typesOfValuesToIndex);

		for(int i=0; i<affectedIndexes.size(); i++){
			if(typesOfValuesToIndex.get(i) instanceof Integer || typesOfValuesToIndex.get(i) instanceof Double){
				affectedIndexes.get(i).put(Double.parseDouble(valuesToIndex.get(i)), row_id);
			}else if(typesOfValuesToIndex.get(i) instanceof String){
				affectedIndexes.get(i).put(valuesToIndex.get(i), row_id);
			}else{
				throw new RuntimeException("non supported type");
			}
			
		}	
		
		return valuesToIndex;
		
	}
	

	protected void performJoin(Tuple stormTupleRcv,
			String inputTupleString, 
			String inputTupleHash,
			boolean isFromFirstEmitter,
			List<Index> oppositeIndexes,
			List<String> valuesToApplyOnIndex,
			TupleStorage oppositeStorage){
		
		TupleStorage tuplesToJoin = new TupleStorage();
		selectTupleToJoin(oppositeStorage, oppositeIndexes, isFromFirstEmitter, valuesToApplyOnIndex, tuplesToJoin);
		join(stormTupleRcv, inputTupleString, isFromFirstEmitter, tuplesToJoin);
	}
	
	private void selectTupleToJoin(TupleStorage oppositeStorage,
			List<Index> oppositeIndexes, boolean isFromFirstEmitter,
			List<String> valuesToApplyOnIndex, TupleStorage tuplesToJoin){
		
		
		if(!_existIndexes ){
			tuplesToJoin.copy(oppositeStorage);
			return;
		}
		
		List<Long> rowIds = new ArrayList<Long>();
		// If there is atleast one index (so we have single join conditions with 1 index per condition)
		// Get the row indices in the storage of the opposite relation that
		// satisfy each join condition (equijoin / inequality)
		// Then take the intersection of the returned row indices since each join condition
		// is separated by AND
		
		for (int i = 0; i < oppositeIndexes.size(); i ++)
		{
			List<Long> currentRowIds = null;
			
			Index currentOpposIndex = oppositeIndexes.get(i);
			String value = valuesToApplyOnIndex.get(i);
			
			
			//Check if the join condition is with numerical value or String
			//If String -> do nothing, if numeric we precalulate in order to optimize
			if(_typeOfValueIndexed.get(i) instanceof Integer || _typeOfValueIndexed.get(i) instanceof Double){
				Double val ;
				if(isFromFirstEmitter){
					val = (Double.parseDouble(value) - (Double)_listCoefB.get(i)) / (Double)_listCoefA.get(i);
				}else{
					val = (Double)_listCoefA.get(i) * Double.parseDouble(value) + (Double)_listCoefB.get(i);
				}
				value = val.toString();
			}
			
			
			int currentOperator = _operatorForIndexes.get(i);
			// Switch inequality operator if the tuple coming is from the other relation
			if (isFromFirstEmitter){
				int operator = currentOperator;
				
				if (operator == ComparisonPredicate.GREATER_OP){
					currentOperator = ComparisonPredicate.LESS_OP;
				}else if (operator == ComparisonPredicate.NONGREATER_OP){
					currentOperator = ComparisonPredicate.NONLESS_OP;
				}else if (operator == ComparisonPredicate.LESS_OP){
					currentOperator = ComparisonPredicate.GREATER_OP;
				}else if (operator == ComparisonPredicate.NONLESS_OP){
					currentOperator = ComparisonPredicate.NONGREATER_OP;	
					
				//then it is an equal or not equal so we dont switch the operator
				}else{
					currentOperator = operator;		
				}			
			}
			
			// Get the values from the index (check type first)
			if(_typeOfValueIndexed.get(i) instanceof String){
				currentRowIds = currentOpposIndex.getValues(value, currentOperator );
			//Even if valueIndexed is at first time an integer with precomputation a*col +b, it become a double
			}else if(_typeOfValueIndexed.get(i) instanceof Double || _typeOfValueIndexed.get(i) instanceof Integer){
				currentRowIds = currentOpposIndex.getValues(Double.parseDouble(value), currentOperator );
			}else{
				throw new RuntimeException("non supported type");
			}
				
			
			// Compute the intersection
			// TODO: Search only within the ids that are in rowIds from previous join conditions
			// If nothing returned (and since we want intersection), no need to proceed.
			if (currentRowIds == null)
				return ;
			
			// If it's the first index, add everything. Else keep the intersection
			if (i == 0)
				rowIds.addAll(currentRowIds);				
			else
				rowIds.retainAll(currentRowIds);
			
			// If empty after intersection, return
			if(rowIds.isEmpty())
				return ;
			
			
		}
		
	
		//generate tuplestorage
		for(Long id: rowIds){
			tuplesToJoin.insert(oppositeStorage.get(id));
			
		}
		
	}
	
	private void join(Tuple stormTuple, 
            String inputTupleString, 
            boolean isFromFirstEmitter,
            TupleStorage oppositeStorage){
		
		if (oppositeStorage == null || oppositeStorage.size() == 0) {
			return;
		}
		
		List<String> affectedTuple = MyUtilities.stringToTuple(inputTupleString, getComponentConfiguration());
 
		for (long i=0; i<oppositeStorage.size(); i++) {
			String oppositeTupleString = oppositeStorage.get(i);
			
			List<String> oppositeTuple= MyUtilities.stringToTuple(oppositeTupleString, getComponentConfiguration());
			List<String> firstTuple, secondTuple;
			if(isFromFirstEmitter){
			    firstTuple = affectedTuple;
			    secondTuple = oppositeTuple;
			}else{
			    firstTuple = oppositeTuple;
			    secondTuple = affectedTuple;
			}
			
			// Check joinCondition
			//if existIndexes == true, the join condition is already checked before
			
			if (_joinPredicate == null || _existIndexes || _joinPredicate.test(firstTuple, secondTuple)) { //if null, cross product
				
				// Create the output tuple by omitting the oppositeJoinKeys (ONLY for equi-joins since they are added 
				// by the first relation), if any (in case of cartesian product there are none)
				List<String> outputTuple = null;
				
				if (!_listColRef.isEmpty())
				{
					//This version, removes all equi-join keys of the second relation from the output tuple
					outputTuple = MyUtilities.createOutputTuple(firstTuple, secondTuple,_listColRef);
				}
				else
				{
					// Cartesian product - Outputs all attributes
					outputTuple = MyUtilities.createOutputTuple(firstTuple, secondTuple);
				}
//System.out.println(firstTuple+"--"+secondTuple);
				applyOperatorsAndSend(stormTuple, outputTuple);

			}
		}	
		
	}

	protected void applyOperatorsAndSend(Tuple stormTupleRcv, List<String> tuple){
		if(MyUtilities.isBatchOutputMode(_batchOutputMillis)){
			try {
				_semAgg.acquire();
			} catch (InterruptedException ex) {}
		}
		tuple = _operatorChain.process(tuple);
		if(MyUtilities.isBatchOutputMode(_batchOutputMillis)){
			_semAgg.release();
		}

		if(tuple == null){
			return;
		}
		_numSentTuples++;
		printTuple(tuple);

		if(MyUtilities.isSending(_hierarchyPosition, _batchOutputMillis)){
			tupleSend(tuple, stormTupleRcv);
		}
	}

	@Override
		public void tupleSend(List<String> tuple, Tuple stormTupleRcv) {
			Values stormTupleSnd = MyUtilities.createTupleValues(tuple, _componentName,
					_hashIndexes, _hashExpressions, _conf);
			MyUtilities.sendTuple(stormTupleSnd, stormTupleRcv, _collector, _conf);
		}

	@Override
		public void batchSend(){
			if(MyUtilities.isBatchOutputMode(_batchOutputMillis)){
				if (_operatorChain != null){
					Operator lastOperator = _operatorChain.getLastOperator();
					if(lastOperator instanceof AggregateOperator){
						try {
							_semAgg.acquire();
						} catch (InterruptedException ex) {}

						//sending
						AggregateOperator agg = (AggregateOperator) lastOperator;
						List<String> tuples = agg.getContent();
						for(String tuple: tuples){
							tupleSend(MyUtilities.stringToTuple(tuple, _conf), null);
						}

						//clearing
						agg.clearStorage();

						_semAgg.release();
					}
				}
			}
		}

	// from IRichBolt
	@Override
		public void cleanup() {
			// TODO Auto-generated method stub

		}

	@Override
		public Map<String,Object> getComponentConfiguration(){
			return _conf;
		}

	@Override
		public void prepare(Map map, TopologyContext tc, OutputCollector collector) {
			_collector=collector;
			_numRemainingParents = MyUtilities.getNumParentTasks(tc, _firstEmitter, _secondEmitter);
		}

	@Override
		public void declareOutputFields(OutputFieldsDeclarer declarer) {
			if(_hierarchyPosition!=FINAL_COMPONENT){ // then its an intermediate stage not the final one
				List<String> outputFields= new ArrayList<String>();
				outputFields.add("TableName");
				outputFields.add("Tuple");
				outputFields.add("Hash");
				declarer.declare(new Fields(outputFields) );
			}else{
				if(!MyUtilities.isAckEveryTuple(_conf)){
					declarer.declareStream(SystemParameters.EOF_STREAM, new Fields(SystemParameters.EOF));
				}
			}
		}

	@Override
		public void printTuple(List<String> tuple){
			if(_printOut){
				if((_operatorChain == null) || !_operatorChain.isBlocking()){
					StringBuilder sb = new StringBuilder();
					sb.append("\nComponent ").append(_componentName);
					sb.append("\nReceived tuples: ").append(_numSentTuples);
					sb.append(" Tuple: ").append(MyUtilities.tupleToString(tuple, _conf));
					LOG.info(sb.toString());
				}
			}
		}

	@Override
		public void printContent() {
			if(_printOut){
				if((_operatorChain!=null) && _operatorChain.isBlocking()){
					Operator lastOperator = _operatorChain.getLastOperator();
					if (lastOperator instanceof AggregateOperator){
						MyUtilities.printBlockingResult(_componentName,
								(AggregateOperator) lastOperator,
								_hierarchyPosition,
								_conf,
								LOG);
					}else{
						MyUtilities.printBlockingResult(_componentName,
								lastOperator.getNumTuplesProcessed(),
								lastOperator.printContent(),
								_hierarchyPosition,
								_conf,
								LOG);
					}
				}
			}
		}

	private boolean receivedDumpSignal(Tuple stormTuple) {
		return stormTuple.getSourceStreamId().equalsIgnoreCase(SystemParameters.DUMP_RESULTS_STREAM);
	}

	// from StormComponent interface
	@Override
		public int getID() {
			return _ID;
		}

	// from StormEmitter interface
	@Override
		public int[] getEmitterIDs() {
			return new int[]{_ID};
		}

	@Override
		public String getName() {
			return _componentName;
		}

	@Override
		public List<Integer> getHashIndexes(){
			return _hashIndexes;
		}

	@Override
		public List<ValueExpression> getHashExpressions() {
			return _hashExpressions;
		}

	@Override
		public String getInfoID() {
			String str = "DestinationStorage " + _componentName + " has ID: " + _ID;
			return str;
		}

}
