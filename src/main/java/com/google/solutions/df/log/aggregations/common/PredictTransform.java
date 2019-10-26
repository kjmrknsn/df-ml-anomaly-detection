/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.solutions.df.log.aggregations.common;

import com.google.auto.value.AutoValue;
import java.util.List;
import org.apache.beam.sdk.schemas.transforms.Group;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Min;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.Row;
import org.apache.commons.math3.ml.distance.EuclideanDistance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoValue
public abstract class PredictTransform
    extends PTransform<PCollection<Row>, PCollection<KV<Row, Row>>> {
  public static final Logger LOG = LoggerFactory.getLogger(PredictTransform.class);

  public abstract PCollectionView<List<CentroidVector>> centroidFeatureVector();

  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder setCentroidFeatureVector(PCollectionView<List<CentroidVector>> values);

    public abstract PredictTransform build();
  }

  public static Builder newBuilder() {
    return new AutoValue_PredictTransform.Builder();
  }

  @Override
  public PCollection<KV<Row, Row>> expand(PCollection<Row> input) {

    return input
        .apply(
            "FindEuclideanDistance",
            ParDo.of(new FindEuclideanDistance(centroidFeatureVector()))
                .withSideInputs(centroidFeatureVector()))
        .setRowSchema(Util.distanceFromCentroidSchema)
        .apply(
            "Find Nearest Centroid Distance",
            Group.<Row>byFieldNames("dst_subnet")
                .aggregateField(
                    "distance_from_centroid", Min.ofDoubles(), "distance_from_nearest_centroid"));
  }

  public static class FindEuclideanDistance extends DoFn<Row, Row> {
    private PCollectionView<List<CentroidVector>> centroidFeature;

    public FindEuclideanDistance(PCollectionView<List<CentroidVector>> centroidFeature) {
      this.centroidFeature = centroidFeature;
    }

    @ProcessElement
    public void processElement(ProcessContext c) {
      double[] aggrFeatureVector = Util.getAggrVector(c.element());
      c.sideInput(centroidFeature)
          .forEach(
              feature -> {
                Double distanceFromCentroid =
                    new EuclideanDistance()
                        .compute(
                            aggrFeatureVector,
                            feature.featureVectors().stream().mapToDouble(d -> d).toArray());

                c.output(
                    Row.withSchema(Util.distanceFromCentroidSchema)
                        .addValues(
                            c.element().getString("subscriber_id"),
                            c.element().getString("dst_subnet"),
                            c.element().getString("transaction_time"),
                            c.element().getInt32("number_of_unique_ips"),
                            c.element().getInt32("number_of_unique_ports"),
                            c.element().getInt32("number_of_records"),
                            c.element().getInt32("max_tx_bytes"),
                            c.element().getInt32("min_tx_bytes"),
                            c.element().getDouble("avg_tx_bytes"),
                            c.element().getInt32("max_rx_bytes"),
                            c.element().getInt32("min_rx_bytes"),
                            c.element().getDouble("avg_rx_bytes"),
                            c.element().getInt32("max_duration"),
                            c.element().getInt32("min_duration"),
                            c.element().getDouble("avg_duration"),
                            feature.centroidId(),
                            feature.radius(),
                            distanceFromCentroid)
                        .build());
              });
    }
  }
}
