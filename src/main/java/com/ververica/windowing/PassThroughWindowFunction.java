package com.ververica.windowing;

import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

/**
 * Window function that puts the input (from the {@link AggregateFunctionWithTypes} into the output.
 *
 * @param <IN> Input type.
 * @param <KEY> Key type.
 */
public class PassThroughWindowFunction<IN, KEY> implements ProcessWindowFunction<KEY, IN, IN> {

  @Override
  public void process(KEY key, TimeWindow window, IN input, Collector<IN> out) {
    out.collect(input);
  }
}
