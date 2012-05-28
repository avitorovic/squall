/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package queryPlans;

import schema.TPCH_Schema;
import components.DataSourceComponent;
import components.JoinComponent;
import components.ThetaJoinComponent;
import conversion.DateConversion;
import conversion.DoubleConversion;
import conversion.IntegerConversion;
import conversion.NumericConversion;
import conversion.StringConversion;
import conversion.TypeConversion;
import expressions.ColumnReference;
import expressions.IntegerYearFromDate;
import expressions.Multiplication;
import expressions.Subtraction;
import expressions.ValueExpression;
import expressions.ValueSpecification;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import operators.AggregateOperator;
import operators.AggregateSumOperator;
import operators.ProjectionOperator;
import operators.SelectionOperator;
import org.apache.log4j.Logger;
import org.omg.CORBA._IDLTypeStub;

import predicates.AndPredicate;
import predicates.BetweenPredicate;
import predicates.ComparisonPredicate;
import predicates.OrPredicate;
import predicates.Predicate;
import queryPlans.QueryPlan;

public class ThetaMultipleJoinOperators {

	private static Logger LOG = Logger.getLogger(ThetaMultipleJoinOperators.class);

	private QueryPlan _queryPlan = new QueryPlan();

	private static final NumericConversion<Double> _doubleConv = new DoubleConversion();
	private static final NumericConversion<Integer> _intConv = new IntegerConversion();

	public ThetaMultipleJoinOperators(String dataPath, String extension, Map conf) {

		// -------------------------------------------------------------------------------------
		List<Integer> hashLineitem = Arrays.asList(0);

		ProjectionOperator projectionLineitem = new ProjectionOperator(
				new int[] { 0, 1, 2 ,5, 6 });

		DataSourceComponent relationLineitem = new DataSourceComponent(
				"LINEITEM", dataPath + "lineitem" + extension,
				TPCH_Schema.lineitem, _queryPlan).setHashIndexes(hashLineitem)
				.setSelection(null).setProjection(projectionLineitem);

		// -------------------------------------------------------------------------------------
		List<Integer> hashOrders= Arrays.asList(0);

		ProjectionOperator projectionOrders = new ProjectionOperator(
				new int[] { 0, 3 });

		DataSourceComponent relationOrders = new DataSourceComponent(
				"ORDERS", dataPath + "orders" + extension,
				TPCH_Schema.orders, _queryPlan).setHashIndexes(hashOrders)
				.setSelection(null).setProjection(projectionOrders);

		//-------------------------------------------------------------------------------------

		
		// -------------------------------------------------------------------------------------
		List<Integer> hashSupplier= Arrays.asList(0);

		ProjectionOperator projectionSupplier = new ProjectionOperator(
				new int[] { 0 });

		DataSourceComponent relationSupplier= new DataSourceComponent(
				"SUPPLIER", dataPath + "supplier" + extension,
				TPCH_Schema.supplier, _queryPlan).setHashIndexes(hashSupplier)
				.setSelection(null).setProjection(projectionSupplier);

		//-------------------------------------------------------------------------------------

		List<Integer> hashPartsSupp= Arrays.asList(0);

		ProjectionOperator projectionPartsSupp = new ProjectionOperator(
				new int[] { 0 ,1 , 2 });
		
		/*ColumnReference colQty = new ColumnReference(_intConv, 2);
		ValueSpecification val9990 = new ValueSpecification(_intConv, 9990);
		SelectionOperator select = new SelectionOperator(new ComparisonPredicate(ComparisonPredicate.GREATER_OP, colQty, val9990));
*/
		DataSourceComponent relationPartsupp= new DataSourceComponent(
				"PARTSUPP", dataPath + "partsupp" + extension,
				TPCH_Schema.partsupp, _queryPlan).setHashIndexes(hashPartsSupp)
				.setSelection(null).setProjection(projectionPartsSupp);

		//-------------------------------------------------------------------------------------

		ColumnReference colRefLineItem = new ColumnReference(_intConv, 0);
		ColumnReference colRefOrders = new ColumnReference(_intConv, 0);
		ComparisonPredicate predL_O1 = new ComparisonPredicate(ComparisonPredicate.EQUAL_OP, colRefLineItem, colRefOrders);
		
		ColumnReference colRefLineItemExtPrice = new ColumnReference(_doubleConv, 3);
		ColumnReference colRefOrdersTotalPrice = new ColumnReference(_doubleConv, 1);
		ValueSpecification val10 = new ValueSpecification(_doubleConv, 10.0);
		Multiplication mult = new Multiplication(_doubleConv, val10, colRefLineItemExtPrice);
		ComparisonPredicate predL_O2 = new ComparisonPredicate(ComparisonPredicate.LESS_OP, mult, colRefOrdersTotalPrice);
		
		AndPredicate predL_O = new AndPredicate(predL_O1, predL_O2);
		
		ThetaJoinComponent LINEITEMS_ORDERSjoin = new ThetaJoinComponent(
				relationLineitem,
				relationOrders,
				_queryPlan)
				.setJoinPredicate(predL_O)
				.setProjection(new ProjectionOperator(new int[]{1, 2, 3,4}))
				;  
		//-------------------------------------------------------------------------------------
		
        SelectionOperator selectionPartSupp = new SelectionOperator(
                new ComparisonPredicate(
                    ComparisonPredicate.GREATER_OP,
                    new ColumnReference(_intConv, 2), 
                    new ValueSpecification(_intConv, 9990)
                ));
        
        ColumnReference colRefSupplier = new ColumnReference(_intConv, 0);
		ColumnReference colRefPartSupp = new ColumnReference(_intConv, 1);
        ComparisonPredicate predS_P = new ComparisonPredicate(ComparisonPredicate.EQUAL_OP, colRefSupplier, colRefPartSupp);
        

		ThetaJoinComponent SUPPLIER_PARTSUPPjoin = new ThetaJoinComponent(
				relationSupplier,
				relationPartsupp,
				_queryPlan)
				.setJoinPredicate(predS_P)
				.setProjection(new ProjectionOperator(new int[]{0,1,2}))
				.setSelection(selectionPartSupp)
				;  
		
		//-------------------------------------------------------------------------------------
		
		
		// set up aggregation function on the StormComponent(Bolt) where join is performed

		//1 - discount
		ValueExpression<Double> substract = new Subtraction(
				_doubleConv,
				new ValueSpecification(_doubleConv, 1.0),
				new ColumnReference(_doubleConv, 3));
		//extendedPrice*(1-discount)
		ValueExpression<Double> product = new Multiplication(
				_doubleConv,
				new ColumnReference(_doubleConv, 2),
				substract);
		AggregateOperator agg = new AggregateSumOperator(_doubleConv, product, conf);
		
	
		ColumnReference colRefL_OPartKey = new ColumnReference(_intConv, 0);
		ColumnReference colRefS_PPartKey = new ColumnReference(_intConv, 1);
		ColumnReference colRefL_OSupKey = new ColumnReference(_intConv, 1);
		ColumnReference colRefS_PSupKey = new ColumnReference(_intConv, 0);
		ComparisonPredicate predL_P1 = new ComparisonPredicate(ComparisonPredicate.EQUAL_OP, colRefL_OPartKey, colRefS_PPartKey);
		ComparisonPredicate predL_P2 = new ComparisonPredicate(ComparisonPredicate.EQUAL_OP, colRefL_OSupKey, colRefS_PSupKey);
		AndPredicate predL_P = new AndPredicate(predL_P1, predL_P2);
		


		ThetaJoinComponent LINEITEMS_ORDERS_SUPPLIER_PARTSUPPjoin = new ThetaJoinComponent(
				LINEITEMS_ORDERSjoin,
				SUPPLIER_PARTSUPPjoin,
				_queryPlan)
				.setJoinPredicate(predL_P)
				.setProjection(new ProjectionOperator(new int[]{0,1, 2, 3}))
				.setAggregation(agg)
				;  
		

		//-------------------------------------------------------------------------------------

		AggregateOperator overallAgg =
			new AggregateSumOperator(_doubleConv, new ColumnReference(_doubleConv, 0), conf); //WHAT COLUMN HERE??

		_queryPlan.setOverallAggregation(overallAgg);


	}

	public QueryPlan getQueryPlan() {
		return _queryPlan;
	}
}