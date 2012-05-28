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

public class ThetaOutputDominatedQuery {

	private static Logger LOG = Logger
			.getLogger(ThetaOutputDominatedQuery.class);

	private QueryPlan _queryPlan = new QueryPlan();

	private static final TypeConversion<Date> _dateConv = new DateConversion();
	private static final NumericConversion<Double> _doubleConv = new DoubleConversion();
	private static final TypeConversion<String> _sc = new StringConversion();

	public ThetaOutputDominatedQuery(String dataPath, String extension, Map conf) {

		// -------------------------------------------------------------------------------------
		List<Integer> hashSupplier = Arrays.asList(0);

		ProjectionOperator projectionSupplier = new ProjectionOperator(
				new int[] { 0 });

		DataSourceComponent relationSupplier = new DataSourceComponent(
				"SUPPLIER", dataPath + "supplier" + extension,
				TPCH_Schema.supplier, _queryPlan).setProjection(
				projectionSupplier).setHashIndexes(hashSupplier);

		// -------------------------------------------------------------------------------------
		List<Integer> hashNation = Arrays.asList(0);

		ProjectionOperator projectionNation = new ProjectionOperator(
				new int[] { 1 });

		DataSourceComponent relationNation = new DataSourceComponent("NATION",
				dataPath + "nation" + extension, TPCH_Schema.nation, _queryPlan)
				.setProjection(projectionNation).setHashIndexes(hashNation);

		AggregateOperator agg = new AggregateSumOperator(_doubleConv,
				new ColumnReference(_doubleConv, 0), conf);
		// Empty parameters = Cartesian Product

		ThetaJoinComponent SUPPLIER_NATIONjoin = new ThetaJoinComponent(
				relationSupplier, relationNation, _queryPlan).setProjection(
				new ProjectionOperator(new int[] { 0 })).setAggregation(agg);
		;

		// -------------------------------------------------------------------------------------

		AggregateOperator overallAgg = new AggregateSumOperator(_doubleConv,
				new ColumnReference(_doubleConv, 0), conf);

		_queryPlan.setOverallAggregation(overallAgg);

	}

	public QueryPlan getQueryPlan() {
		return _queryPlan;
	}
}