//
// Copyright 2020 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package com.google.privacy.differentialprivacy;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.lang.Double.NaN;
import static java.util.stream.Collectors.joining;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.Range;
import com.google.differentialprivacy.SummaryOuterClass.MechanismType;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests the accuracy of {@link BoundedMean}. The test mocks {@link Noise} instance which generates
 * zero noise.
 *
 * <p>Statistical and DP properties of the algorithm are tested in
 * {@link com.google.privacy.differentialprivacy.statistical.BoundedMeanDpTest}.
 */
@RunWith(JUnit4.class)
public class BoundedMeanTest {
  private static final double EPSILON = 1.0;
  private static final double DELTA = 0.123;
  private static final double ALPHA = 0.1;
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();
  @Mock private Noise noise;
  private BoundedMean mean;

  @Before
  public void setUp() {
    when(noise.getMechanismType()).thenReturn(MechanismType.GAUSSIAN);
    // Mock the noise mechanism so that it does not add any noise.
    mockDoubleNoise(0);
    mockLongNoise(0);
    mockDoubleConfInt(ConfidenceInterval.create(0, 0));
    mockLongConfInt(ConfidenceInterval.create(0, 0));

    mean =
        BoundedMean.builder()
            .epsilon(EPSILON)
            .delta(DELTA)
            .noise(noise)
            .maxPartitionsContributed(1)
            .maxContributionsPerPartition(1)
            .lower(1.0)
            .upper(9.0)
            .build();
  }

  @Test
  public void addEntry() {
    mean.addEntry(2.0);
    mean.addEntry(4.0);
    mean.addEntry(6.0);
    mean.addEntry(8.0);

    assertThat(mean.computeResult()).isEqualTo(5.0);
  }

  @Test
  public void addEntries() {
    mean.addEntries(Arrays.asList(2.0, 4.0, 6.0, 8.0));
    assertThat(mean.computeResult()).isEqualTo(5.0);
  }

  @Test
  public void addEntry_Nan_ignored() {
    // Add NaN - no exception is thrown.
    mean.addEntry(NaN);
    // Add any values (let's say 7 and 9). Verify that the result is equal to their mean.
    mean.addEntry(7);
    mean.addEntry(9);
    assertThat(mean.computeResult()).isEqualTo(8.0);
  }

  // An attempt to compute mean several times results in an exception.
  @Test
  public void computeResult_multipleCalls_throwsException() {
    mean.computeResult();
    assertThrows(IllegalStateException.class, () -> mean.computeResult());
  }

  // Input values are clamped to the upper and lower bounds.
  @Test
  public void addEntry_clampsInput() {
    mean =
        BoundedMean.builder()
            .epsilon(EPSILON)
            .delta(DELTA)
            .noise(noise)
            .maxPartitionsContributed(1)
            .maxContributionsPerPartition(1)
            .lower(0.0)
            .upper(2.0)
            .build();

    mean.addEntry(-1.0); // will be clamped to 0
    mean.addEntry(1.0); // will not be clamped
    mean.addEntry(10.0); // will be clamped to 2

    assertThat(mean.computeResult()).isEqualTo(/* (0 + 1 + 2) / 3 */ 1.0);
  }

  @Test
  public void computeResult_singleInput_returnsInput() {
    mean =
        BoundedMean.builder()
            .epsilon(EPSILON)
            .delta(DELTA)
            .noise(noise)
            .maxPartitionsContributed(1)
            .maxContributionsPerPartition(1)
            .lower(1.0)
            .upper(9.0)
            .build();

    mean.addEntry(3.0);
    assertThat(mean.computeResult()).isEqualTo(3.0);
  }

  @Test
  public void computeResult_noInput_returnsMidpoint() {
    mean =
        BoundedMean.builder()
            .epsilon(EPSILON)
            .delta(DELTA)
            .noise(noise)
            .maxPartitionsContributed(1)
            .maxContributionsPerPartition(1)
            .lower(1.0)
            .upper(9.0)
            .build();

    assertThat(mean.computeResult()).isEqualTo(/* midpoint = (1 + 9) / 2 */ 5.0);
  }

  @Test
  public void computeResult_callsNoiseCorrectly() {
    int maxPartitionsContributed = 1;
    int maxContributionsPerPartition = 3;
    mean =
        BoundedMean.builder()
            .epsilon(EPSILON)
            .delta(DELTA)
            .noise(noise)
            .maxPartitionsContributed(maxPartitionsContributed)
            .maxContributionsPerPartition(maxContributionsPerPartition)
            .lower(1.0)
            .upper(9.0)
            .build();
    mean.addEntry(2.0);
    mean.addEntry(4.0);
    mean.computeResult();

    // Noising normalized sum.
    verify(noise)
        .addNoise(
            eq(/* x1 + x2 - midpoint * count = 2 + 4 - 5 * 2*/ -4.0),
            eq(maxPartitionsContributed),
            eq(/* maxContributionsPerPartition * (upper - lower) / 2 = 3 * (9 - 1) / 2 */ 12.0),
            eq(EPSILON / 2.0),
            eq(DELTA / 2.0));

    // Noising count.
    verify(noise)
        .addNoise(
            eq(/* count */ 2L),
            eq(maxPartitionsContributed),
            eq(
                /* sensitivity of count  = maxContributionsPerPartition*/ (long)
                    maxContributionsPerPartition),
            eq(EPSILON / 2.0),
            eq(DELTA / 2.0));
  }

  @Test
  public void computeResult_addsNoiseToSum() {
    // Mock the noise mechanism so that it adds noise to the sum == 10.0.
    mockDoubleNoise(10);
    // Mock the noise mechanism so that it adds noise to the count == 0.
    mockLongNoise(0);

    mean =
        BoundedMean.builder()
            .epsilon(EPSILON)
            .delta(DELTA)
            .noise(noise)
            .maxPartitionsContributed(1)
            .maxContributionsPerPartition(1)
            .lower(-100)
            .upper(100)
            .build();

    mean.addEntry(20);
    mean.addEntry(20);
    // midpoint = (lower + upper) / 2 = 0,
    // noised_normalized_sum = (x1 + x2) - midpoint * count + noise = 20 + 20 - 0 + 10 = 50,
    // noised_count = count + noise = 2 + 0 = 2,
    // BoundedMean.computeResult() = noised_normalized_sum / noised_count + midpoint = 50 / 2 + 0.
    assertThat(mean.computeResult()).isEqualTo(25);
  }

  @Test
  public void computeResult_addsNoiseToCount() {
    // Mock the noise mechanism so that it adds noise to the sum == 0.0.
    mockDoubleNoise(0);
    // Mock the noise mechanism so that it adds noise to the count == 2.
    mockLongNoise(2);

    mean =
        BoundedMean.builder()
            .epsilon(EPSILON)
            .delta(DELTA)
            .noise(noise)
            .maxPartitionsContributed(1)
            .maxContributionsPerPartition(1)
            .lower(-100)
            .upper(100)
            .build();

    mean.addEntry(20);
    mean.addEntry(20);
    // midpoint = (lower + upper) / 2 = 0,
    // noised_normalized_sum = (x1 + x2) - midpoint * count + noise = 20 + 20 - 0 + 0 = 40,
    // noised_count = count + noise = 2 + 2 = 4,
    // BoundedMean.computeResult() = noised_normalized_sum / noised_count + midpoint = 40 / 4 + 0.
    assertThat(mean.computeResult()).isEqualTo(10);
  }

  @Test
  public void computeResult_clampsTooHighAverage() {
    // We need to have non-zero noise to sum in order to get average which needs to be clamped
    // (i.e., outside of the bounds). If no noise is added then the average will always be within
    // the bounds because the input values are clamped.
    // The noise added to sum is 100.
    mockDoubleNoise(100);
    // The noise added to sum is 0.
    mockLongNoise(0);

    mean =
        BoundedMean.builder()
            .epsilon(EPSILON)
            .delta(DELTA)
            .noise(noise)
            .maxPartitionsContributed(1)
            .maxContributionsPerPartition(1)
            .lower(0)
            .upper(10)
            .build();

    mean.addEntry(5.0);
    mean.addEntry(5.0);
    // midpoint = (lower + upper) / 2 = 5,
    // noised_normalized_sum = (x1 + x2) - midpoint * count + noise = 5 + 5 - 5 * 2 + 100 = 100,
    // noised_count = count + noise = 2 + 0 = 2,
    // non_clamped_average = noised_normalized_sum / noised_count + midpoint = 50 + 5 = 55,
    // BoundedMean.computeResult = clamp(non_clamped_average) = 10 (upper bound).
    assertThat(mean.computeResult()).isEqualTo(10);
  }

  @Test
  public void computeResult_clampsTooLowAverage() {
    // We need to add non-zero noise to sum in order to get average which needs to be clamped
    // (i.e., outside of the bounds). If no noise is added then the average will always be within
    // the bounds because the input values are clamped.
    // The noise added to sum is 100.
    mockDoubleNoise(-100);
    // The noise added to sum is 0.
    mockLongNoise(0);

    mean =
        BoundedMean.builder()
            .epsilon(EPSILON)
            .delta(DELTA)
            .noise(noise)
            .maxPartitionsContributed(1)
            .maxContributionsPerPartition(1)
            .lower(0)
            .upper(10)
            .build();

    mean.addEntry(5.0);
    mean.addEntry(5.0);
    // midpoint = (lower + upper) / 2 = 5,
    // noised_normalized_sum = (x1 + x2) - midpoint * count + noise = 5 + 5 - 5 * 2 - 100 = -100,
    // noised_count = count + noise = 2 + 0 = 2,
    // non_clamped_average = noised_normalized_sum / noised_count + midpoint = -50 + 5 = -45,
    // BoundedMean.computeResult = clamp(non_clamped_average) = 0 (lower bound).
    assertThat(mean.computeResult()).isEqualTo(0);
  }

  /**
   * This test was designed to be not deterministic. It goes along with deterministic analogues in
   * order to ensure that they don't miss something.
   */
  @Test
  public void computeResult_resultAlwaysInsideProvidedBoundaries() {
    int datasetSize = 10;
    for (int i = 0; i < 100; ++i) {
      Random random = new Random();
      double lower = random.nextDouble() * 100;
      double upper = lower + random.nextDouble() * 100;

      mean =
          BoundedMean.builder()
              .epsilon(EPSILON)
              .noise(new LaplaceNoise())
              .maxPartitionsContributed(1)
              .maxContributionsPerPartition(1)
              .lower(lower)
              .upper(upper)
              .build();

      List<Double> dataset =
          random
              .doubles()
              .map(x -> x * 300 * getRandomSign(random))
              .limit(datasetSize)
              .boxed()
              .collect(toImmutableList());

      mean.addEntries(dataset);

      assertWithMessage(
              "lower = %s\nupper = %s\ndataset = [%s]",
              lower,
              upper,
              dataset.stream().map(x -> Double.toString(x)).collect(joining(",\n")))
          .that(mean.computeResult())
          .isIn(Range.closed(lower, upper));
    }
  }
  
  @Test
  public void computeConfidenceInterval_calledBeforeResult() {
    assertThrows(IllegalStateException.class, () -> mean.computeConfidenceInterval(ALPHA));
  }

  @Test
  public void computeConfidenceInterval_callsNoiseCorrectly() {
    int maxPartitionsContributed = 1;
    int maxContributionsPerPartition = 3;
    double alpha = 0.5;
    mean =
        BoundedMean.builder()
            .epsilon(EPSILON)
            .delta(DELTA)
            .noise(noise)
            .maxPartitionsContributed(maxPartitionsContributed)
            .maxContributionsPerPartition(maxContributionsPerPartition)
            .lower(2.0)
            .upper(10.0)
            .build();

    mean.addEntry(3.0);
    mean.addEntry(7.0);
    mean.computeResult();
    mean.computeConfidenceInterval(alpha, alpha / 2.0);

    // Confidence interval for normalized sum.
    verify(noise)
        .computeConfidenceInterval(
            eq(/* x1 + x2 - midpoint * count = 3.0 + 7.0 - 6.0 * 2.0 = */ -2.0),
            eq(maxPartitionsContributed),
            eq(
                /* maxContributionsPerPartition * (upper - lower) / 2.0
                = 3.0 * (10.0 - 2.0) / 2.0 = */ 12.0),
            eq(EPSILON / 2.0),
            eq(DELTA / 2.0),
            eq(alpha / 2.0));

    // Confidence interval for count.
    verify(noise)
        .computeConfidenceInterval(
            eq(/* count of added elements = */ 2L),
            eq(maxPartitionsContributed),
            eq((long) maxContributionsPerPartition),
            eq(EPSILON / 2.0),
            eq(DELTA / 2.0),
            eq(/* alphaDen = (alpha - alphaNum) / (1 - alphaNum) = 0.25 / 0.75 = */ 1.0 / 3.0));
  }

  @Test
  public void computeConfidenceInterval_positiveSumUpperBound() {
    // Sum confidence interval.
    mockDoubleConfInt(ConfidenceInterval.create(0.0, 5.0));
    // Count confidence interval.
    mockLongConfInt(ConfidenceInterval.create(2.0, 5.0));
    mean.computeResult();

    // mean_upperbound = sum_upperBound / count_lowerBound + midPoint = 5.0 / 2.0 + (1.0 + 9.0) /
    // 2.0 = 7.5
    assertThat(mean.computeConfidenceInterval(ALPHA).upperBound()) // parameters are ignored.
        .isEqualTo(7.5);
  }

  @Test
  public void computeConfidenceInterval_negativeSumUpperBound() {
    // Sum confidence interval.
    mockDoubleConfInt(ConfidenceInterval.create(-10.0, -5.0));
    // Count confidence interval.
    mockLongConfInt(ConfidenceInterval.create(2.0, 5.0));
    mean.computeResult();

    // mean_upperbound = sum_upperBound / count_upperBound + midPoint = -5.0 / 5.0 + (1.0 + 9.0) /
    // 2.0 = 4.0
    assertThat(mean.computeConfidenceInterval(ALPHA).upperBound()) // parameters are ignored.
        .isEqualTo(4.0);
  }

  @Test
  public void computeConfidenceInterval_positiveSumLowerBound() {
    // Sum confidence interval.
    mockDoubleConfInt(ConfidenceInterval.create(5.0, 10.0));
    // Count confidence interval.
    mockLongConfInt(ConfidenceInterval.create(2.0, 5.0));
    mean.computeResult();

    // mean_lowerBound = sum_lowerBound / count_upperBound + midPoint = 5.0 / 5.0 + (1.0 + 9.0) /
    // 2.0 = 6.0
    assertThat(mean.computeConfidenceInterval(ALPHA).lowerBound()) // parameters are ignored.
        .isEqualTo(6.0);
  }

  @Test
  public void computeConfidenceInterval_negativeSumLowerBound() {
    // Sum confidence interval.
    mockDoubleConfInt(ConfidenceInterval.create(-5.0, 0.0));
    // Count confidence interval.
    mockLongConfInt(ConfidenceInterval.create(2.0, 5.0));
    mean.computeResult();

    // mean_lowerBound = sum_lowerBound / count_lowerBound + midPoint = -5.0 / 2.0 + (1.0 + 9.0) /
    // 2.0 = 2.5
    assertThat(mean.computeConfidenceInterval(ALPHA).lowerBound()) // parameters are ignored.
        .isEqualTo(2.5);
  }

  @Test
  public void computeConfidenceInterval_clampTooLowBounds() {
    // Sum confidence interval, large negative values are used to test lower clamping.
    mockDoubleConfInt(ConfidenceInterval.create(-100.0, -50.0));
    // Count confidence interval.
    mockLongConfInt(ConfidenceInterval.create(2.0, 5.0));
    mean.computeResult();

    // Both bounds should be clamped to lower = 1.0
    assertThat(mean.computeConfidenceInterval(ALPHA)) // parameters are ignored.
        .isEqualTo(ConfidenceInterval.create(1.0, 1.0));
  }

  @Test
  public void computeConfidenceInterval_clampTooHighBounds() {
    // Sum confidence interval, large positive values are used to test upper clamping.
    mockDoubleConfInt(ConfidenceInterval.create(50.0, 100.0));
    // Count confidence interval.
    mockLongConfInt(ConfidenceInterval.create(2.0, 5.0));
    mean.computeResult();

    // Both bounds should be clamped to upper = 9.0
    assertThat(mean.computeConfidenceInterval(ALPHA)) // parameters are ignored.
        .isEqualTo(ConfidenceInterval.create(9.0, 9.0));
  }

  @Test
  public void computeConfidenceInterval_boundsAlwaysInsideProvidedBoundaries() {
    double lower = 0.0;
    double upper = 1.0;
    mean =
        BoundedMean.builder()
            .epsilon(EPSILON)
            .delta(null)
            .noise(new LaplaceNoise())
            .maxPartitionsContributed(1)
            .maxContributionsPerPartition(1)
            .lower(lower)
            .upper(upper)
            .build();
    mean.addEntry(0.5);
    mean.computeResult();
    for (double alpha : new double[] {0.1, 0.3, 0.5, 0.9, 0.99}) {
      for (double alphaNum : new double[] {0.001, 0.025, 0.005, 0.075, 0.09}) {
        ConfidenceInterval confInt = mean.computeConfidenceInterval(alpha, alphaNum);

        assertThat(confInt.lowerBound()).isIn(Range.closed(lower, upper));
        assertThat(confInt.upperBound()).isIn(Range.closed(lower, upper));
      }
    }
  }

  @Test
  public void merge_basicExample_meansValues() {
    BoundedMean targetMean = getBoundedMeanBuilderWithFields().build();
    BoundedMean sourceMean = getBoundedMeanBuilderWithFields().build();

    targetMean.addEntry(1);
    sourceMean.addEntry(9);

    targetMean.mergeWith(sourceMean.getSerializableSummary());

    assertThat(targetMean.computeResult()).isEqualTo(5);
  }

  @Test
  public void merge_calledTwice_meansValues() {
    BoundedMean targetMean = getBoundedMeanBuilderWithFields().build();
    BoundedMean sourceMean1 = getBoundedMeanBuilderWithFields().build();
    BoundedMean sourceMean2 = getBoundedMeanBuilderWithFields().build();

    targetMean.addEntry(1);
    sourceMean1.addEntry(2);
    sourceMean2.addEntry(3);

    targetMean.mergeWith(sourceMean1.getSerializableSummary());
    targetMean.mergeWith(sourceMean2.getSerializableSummary());

    assertThat(targetMean.computeResult()).isEqualTo(2);
  }

  @Test
  public void merge_nullDelta_noException() {
    BoundedMean targetMean =
        getBoundedMeanBuilderWithFields().noise(new LaplaceNoise()).delta(null).build();
    BoundedMean sourceMean =
        getBoundedMeanBuilderWithFields().noise(new LaplaceNoise()).delta(null).build();
    // No exception should be thrown.
    targetMean.mergeWith(sourceMean.getSerializableSummary());
  }

  @Test
  public void merge_differentEpsilon_throwsException() {
    BoundedMean targetMean = getBoundedMeanBuilderWithFields().epsilon(EPSILON).build();
    BoundedMean sourceMean = getBoundedMeanBuilderWithFields().epsilon(2 * EPSILON).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> targetMean.mergeWith(sourceMean.getSerializableSummary()));
  }

  @Test
  public void merge_differentDelta_throwsException() {
    BoundedMean targetMean = getBoundedMeanBuilderWithFields().delta(DELTA).build();
    BoundedMean sourceMean = getBoundedMeanBuilderWithFields().delta(2 * DELTA).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> targetMean.mergeWith(sourceMean.getSerializableSummary()));
  }

  @Test
  public void merge_differentNoise_throwsException() {
    BoundedMean targetMean =
        getBoundedMeanBuilderWithFields().noise(new LaplaceNoise()).delta(null).build();
    BoundedMean sourceMean = getBoundedMeanBuilderWithFields().noise(new GaussianNoise()).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> targetMean.mergeWith(sourceMean.getSerializableSummary()));
  }

  @Test
  public void merge_differentMaxPartitionsContributed_throwsException() {
    BoundedMean targetMean = getBoundedMeanBuilderWithFields().maxPartitionsContributed(1).build();
    BoundedMean sourceMean = getBoundedMeanBuilderWithFields().maxPartitionsContributed(2).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> targetMean.mergeWith(sourceMean.getSerializableSummary()));
  }

  @Test
  public void merge_differentMaxContributionsPerPartition_throwsException() {
    BoundedMean targetMean =
        getBoundedMeanBuilderWithFields().maxContributionsPerPartition(1).build();
    BoundedMean sourceMean =
        getBoundedMeanBuilderWithFields().maxContributionsPerPartition(2).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> targetMean.mergeWith(sourceMean.getSerializableSummary()));
  }

  @Test
  public void merge_differentLowerBounds_throwsException() {
    BoundedMean targetMean = getBoundedMeanBuilderWithFields().lower(-1).build();
    BoundedMean sourceMean = getBoundedMeanBuilderWithFields().lower(-100).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> targetMean.mergeWith(sourceMean.getSerializableSummary()));
  }

  @Test
  public void merge_differentUpperBounds_throwsException() {
    BoundedMean targetMean = getBoundedMeanBuilderWithFields().upper(1).build();
    BoundedMean sourceMean = getBoundedMeanBuilderWithFields().upper(100).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> targetMean.mergeWith(sourceMean.getSerializableSummary()));
  }

  @Test
  public void merge_calledAfterComputeResult_ontargetMean_throwsException() {
    BoundedMean targetMean = getBoundedMeanBuilderWithFields().build();
    BoundedMean sourceMean = getBoundedMeanBuilderWithFields().build();

    targetMean.computeResult();
    assertThrows(
        IllegalStateException.class,
        () -> targetMean.mergeWith(sourceMean.getSerializableSummary()));
  }

  @Test
  public void merge_calledAfterComputeResult_onsourceMean_throwsException() {
    BoundedMean targetMean = getBoundedMeanBuilderWithFields().build();
    BoundedMean sourceMean = getBoundedMeanBuilderWithFields().build();

    sourceMean.computeResult();
    assertThrows(
        IllegalStateException.class,
        () -> targetMean.mergeWith(sourceMean.getSerializableSummary()));
  }

  @Test
  public void merge_calledAfterSerialization_ontargetMean_throwsException() {
    BoundedMean targetMean = getBoundedMeanBuilderWithFields().build();
    BoundedMean sourceMean = getBoundedMeanBuilderWithFields().build();

    targetMean.getSerializableSummary();
    assertThrows(
        IllegalStateException.class,
        () -> targetMean.mergeWith(sourceMean.getSerializableSummary()));
  }

  private BoundedMean.Params.Builder getBoundedMeanBuilderWithFields() {
    return BoundedMean.builder()
        .epsilon(EPSILON)
        .delta(DELTA)
        .noise(noise)
        .maxPartitionsContributed(1)
        // lower, upper and, maxContributionsPerPartition have arbitrarily chosen values.
        .maxContributionsPerPartition(10)
        .lower(-10)
        .upper(10);
  }

  private void mockDoubleNoise(double value) {
    when(noise.addNoise(anyDouble(), anyInt(), anyDouble(), anyDouble(), anyDouble()))
        .thenAnswer(invocation -> (double) invocation.getArguments()[0] + value);
  }

  private void mockLongNoise(long value) {
    when(noise.addNoise(anyLong(), anyInt(), anyLong(), anyDouble(), anyDouble()))
        .thenAnswer(invocation -> (long) invocation.getArguments()[0] + value);
  }

  private void mockDoubleConfInt(ConfidenceInterval confInt) {
    when(noise.computeConfidenceInterval(
        anyDouble(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
        .thenAnswer(invocation -> confInt);
  }

  private void mockLongConfInt(ConfidenceInterval confInt) {
    when(noise.computeConfidenceInterval(
        anyLong(), anyInt(), anyLong(), anyDouble(), anyDouble(), anyDouble()))
        .thenAnswer(invocation -> confInt);
  }

  private static int getRandomSign(Random random) {
    return random.nextBoolean() ? 1 : -1;
  }
}
