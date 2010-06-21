package de.fuberlin.wiwiss.silk.linkspec.aggregator

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import de.fuberlin.wiwiss.silk.linkspec.aggegrator.EuclidAggregator
import de.fuberlin.wiwiss.silk.linkspec.util.approximatelyEqualTo

class EuclidAggregatorTest extends FlatSpec with ShouldMatchers
{
    val aggregator = new EuclidAggregator()

    "EuclidAggregator" should "compute the weighted euclidian distance" in
    {
        aggregator.evaluate((1, 1.0) :: (1, 1.0) :: (1, 1.0) :: Nil).get should be (approximatelyEqualTo (1.0))
        aggregator.evaluate((1, 1.0) :: (1, 0.0) :: Nil).get should be (approximatelyEqualTo (0.707107))
        aggregator.evaluate((1, 0.4) :: (1, 0.5) :: (1, 0.6) :: Nil).get should be (approximatelyEqualTo (0.506623))
        aggregator.evaluate((1, 0.0) :: (1, 0.0) :: Nil).get should be (approximatelyEqualTo (0.0))
        aggregator.evaluate((2, 1.0) :: (1, 0.0) :: (1, 0.0) :: Nil).get should be (approximatelyEqualTo (0.707107))
        aggregator.evaluate((1, 0.4) :: (2, 0.5) :: (3, 0.6) :: Nil).get should be (approximatelyEqualTo (0.538516))
    }
}