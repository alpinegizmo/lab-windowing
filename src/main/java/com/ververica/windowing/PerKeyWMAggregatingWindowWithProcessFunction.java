package com.ververica.windowing;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;

import com.ververica.timestamps.KeyedBoundedOutOfOrdernessWatermark;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.api.java.typeutils.TypeExtractor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.metrics.Counter;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.operators.TimestampedCollector;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.assigners.WindowAssigner;
import org.apache.flink.streaming.api.windowing.assigners.WindowAssigner.WindowAssignerContext;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.triggers.TriggerResult;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.streaming.runtime.operators.windowing.functions.InternalWindowFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.apache.flink.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @param <Key> The Type of the key.
 * @param <IN> The type of the values that are aggregated (input values)
 * @param <ACC> The type of the accumulator (intermediate aggregate state).
 * @param <OUT> The type of the aggregated result
 */
public class PerKeyWMAggregatingWindowWithProcessFunction<Key, IN, OUT, ACC, ACC_OUT>
    extends KeyedProcessFunction<Key, IN, OUT> implements ResultTypeQueryable<OUT> {

  private static final Logger LOG =
      LoggerFactory.getLogger(PerKeyWMAggregatingWindowWithProcessFunction.class);

  private final KeyedBoundedOutOfOrdernessWatermark watermark;

  private TypeInformation<OUT> producedType = null;

  private transient MapState<Long, Tuple2<Long, ACC>> windowState;
  private transient MapState<Long, Void> timerState;

  private final WindowAssigner<Object, TimeWindow> windowAssigner;
  private final AggregateFunctionWithTypes<IN, ACC, ACC_OUT> windowAggregateFunction;
  private final ProcessWindowFunction<Key, ACC_OUT, OUT> windowProcessFunction;

  private TriggerResult windowFireMode = TriggerResult.FIRE;
  /**
   * {@link OutputTag} to use for late arriving events. Elements for which {@code
   * window.maxTimestamp + allowedLateness} is smaller than the current watermark will be emitted to
   * this.
   */
  protected OutputTag<IN> lateDataOutputTag;

  private static final String LATE_ELEMENTS_DROPPED_METRIC_NAME = "numLateRecordsDropped";

  protected transient Counter numLateRecordsDropped;

  /**
   * The allowed lateness for elements. This is used for:
   *
   * <ul>
   *   <li>Deciding if an element should be dropped from a window due to lateness.
   *   <li>Clearing the state of a window if the system time passes the {@code window.maxTimestamp +
   *       allowedLateness} landmark.
   * </ul>
   */
  protected long allowedLateness;

  private transient WindowAssignerContext windowAssignerContext;

  public PerKeyWMAggregatingWindowWithProcessFunction(
      KeyedBoundedOutOfOrdernessWatermark watermark,
      WindowAssigner<Object, TimeWindow> windowAssigner,
      AggregateFunctionWithTypes<IN, ACC, ACC_OUT> windowAggregateFunction,
      ProcessWindowFunction<Key, ACC_OUT, OUT> windowProcessFunction) {
    this.watermark = watermark;
    this.windowProcessFunction = windowProcessFunction;

    checkNotNull(windowAssigner);
    checkArgument(
        windowAssigner instanceof SlidingEventTimeWindows
            || windowAssigner instanceof TumblingEventTimeWindows,
        "unsupported window assigner: not a sliding or tumbling event-time window assigner: {}",
        windowAssigner);
    // check for eventTime should be redundant, but be safe for now
    checkArgument(windowAssigner.isEventTime(), "only event time supported");

    this.windowAssigner = windowAssigner;
    this.windowAggregateFunction = checkNotNull(windowAggregateFunction);
  }

  @Override
  public void processElement(IN value, Context ctx, Collector<OUT> out) throws Exception {
    setWindowContext(ctx);

    long currentTimestamp = ctx.timestamp();
    Long currentWatermark = watermark.getCurrentWatermark(ctx);

    Collection<TimeWindow> windows =
        windowAssigner.assignWindows(value, currentTimestamp, windowAssignerContext);

    // if element is handled by none of assigned elementWindows
    boolean isSkippedElement = true;
    for (TimeWindow window : windows) {
      // drop if the window is already late
      if (isWindowLate(window, currentWatermark)) {
        continue;
      }
      isSkippedElement = false;

      long stateKey = windowToStateKey(window);
      Tuple2<Long, ACC> stateEntry = windowState.get(stateKey);
      boolean firstInWindow = stateEntry == null;
      if (firstInWindow) {
        stateEntry = Tuple2.of(window.getStart(), windowAggregateFunction.createAccumulator());
      }
      stateEntry.f1 = windowAggregateFunction.add(value, stateEntry.f1);
      windowState.put(stateKey, stateEntry);

      boolean cleanupTimerNeeded = firstInWindow && allowedLateness > 0;
      if (window.maxTimestamp() <= currentWatermark) {
        // event within allowed lateness
        emitWindowContents(out, window, stateEntry.f1, ctx.getCurrentKey());

        if (windowFireMode.isPurge()) {
          windowState.remove(stateKey);
        } else {
          cleanupTimerNeeded = firstInWindow;
        }
      } else if (firstInWindow) {
        // only register once (avoid state access)
        registerRegularEndTimer(window, ctx);
      }

      if (cleanupTimerNeeded) {
        // only register once (avoid state access)
        registerCleanupTimer(window, ctx);
      }
    }

    // side output input event if
    // - element not handled by any window
    // - late arriving tag has been set
    // - current timestamp + allowed lateness no less than element timestamp
    if (isSkippedElement && isElementLate(ctx, currentWatermark)) {
      if (lateDataOutputTag != null) {
        sideOutput(value, ctx);
      } else {
        this.numLateRecordsDropped.inc();
      }
    }

    currentWatermark = watermark.updateCurrentWatermark(ctx);
    processTimers(ctx, currentWatermark, out);
  }

  private void processTimers(Context ctx, Long currentWatermark, Collector<OUT> out)
      throws Exception {
    Key currentKey = ctx.getCurrentKey();
    Iterator<Long> it = timerState.keys().iterator();
    List<Long> timesToFire = new ArrayList<>();
    while (it.hasNext()) {
      Long timestamp = it.next();
      if (timestamp <= currentWatermark) {
        timesToFire.add(timestamp);
        it.remove();
      }
    }
    // fire timers in order of their timestamps
    Collections.sort(timesToFire);
    for (long timestamp : timesToFire) {
      onTimer(timestamp, currentKey, out);
    }
  }

  private void onTimer(long timestamp, Key currentKey, Collector<OUT> out) throws Exception {
    /*
     * note: the timer could be for either or both of:
     * - the timer for window.maxTime()
     * - the cleanup timer (window.maxTime() + allowedLateness)
     */
    // a window end timer? (assume all windows have the same length - sliding and tumbling
    // windows)
    long windowEndStateKey = regularEndTimeToStateKey(timestamp);
    long cleanupStateKey = cleanupTimeToStateKey(timestamp);
    Tuple2<Long, ACC> currentState = windowState.get(windowEndStateKey);
    if (currentState != null) {
      emitWindowContents(
          out, new TimeWindow(currentState.f0, windowEndStateKey), currentState.f1, currentKey);

      if (windowFireMode.isPurge()) {
        windowState.remove(windowEndStateKey);
      }
    }

    // if it exists, this is always a cleanup timer!
    windowState.remove(cleanupStateKey);
  }

  /** Emits the contents of the given window using the {@link InternalWindowFunction}. */
  private void emitWindowContents(
      Collector<OUT> out, TimeWindow window, ACC contents, Key currentKey) throws Exception {
    ((TimestampedCollector<OUT>) out).setAbsoluteTimestamp(window.maxTimestamp());
    ACC_OUT result = windowAggregateFunction.getResult(contents);
    windowProcessFunction.process(currentKey, window, result, out);
  }

  /**
   * Write skipped late arriving element to SideOutput.
   *
   * @param element skipped late arriving element to side output
   */
  protected void sideOutput(IN element, Context ctx) {
    ctx.output(lateDataOutputTag, element);
  }

  /**
   * Returns {@code true} if the watermark is after the end timestamp plus the allowed lateness of
   * the given window.
   */
  protected boolean isWindowLate(TimeWindow window, long currentWatermark) {
    return cleanupTime(window) <= currentWatermark;
  }

  /**
   * Decide if a record is currently late, based on current watermark and allowed lateness.
   *
   * @return The element for which should be considered when sideoutputs
   */
  protected boolean isElementLate(Context ctx, long currentWatermark) {
    return ctx.timestamp() + allowedLateness <= currentWatermark;
  }

  /**
   * Registers a timer to the regular window end (when its max time has passed).
   *
   * @param window the window whose state to evaluate
   */
  protected void registerRegularEndTimer(TimeWindow window, Context ctx) throws Exception {
    timerState.put(window.maxTimestamp(), null);
  }

  /**
   * Registers a timer to cleanup the content of the window.
   *
   * @param window the window whose state to discard
   */
  protected void registerCleanupTimer(TimeWindow window, Context ctx) throws Exception {
    long cleanupTime = cleanupTime(window);
    if (cleanupTime == Long.MAX_VALUE) {
      // don't set a GC timer for "end of time"
      return;
    }

    timerState.put(cleanupTime, null);
  }

  /**
   * Returns the cleanup time for a window, which is {@code window.maxTimestamp + allowedLateness}.
   * In case this leads to a value greater than {@link Long#MAX_VALUE} then a cleanup time of {@link
   * Long#MAX_VALUE} is returned.
   *
   * @param window the window whose cleanup time we are computing.
   */
  private long cleanupTime(TimeWindow window) {
    long cleanupTime = window.maxTimestamp() + allowedLateness;
    return cleanupTime >= window.maxTimestamp() ? cleanupTime : Long.MAX_VALUE;
  }

  /**
   * Extracts the state key (window end) from the window.
   *
   * @param window the window
   * @return key to use for accessing its state
   */
  private long windowToStateKey(TimeWindow window) {
    return window.getEnd();
  }

  /**
   * Reverses the calculation from {@link #cleanupTime(TimeWindow)} to calculate the state key for a
   * given timer's time.
   *
   * @param cleanupTime cleanup time (as from the registered timer)
   * @return state key (window end)
   */
  private long cleanupTimeToStateKey(long cleanupTime) {
    checkArgument(cleanupTime != Long.MAX_VALUE);

    long windowMaxTimestamp = cleanupTime - allowedLateness;
    return windowMaxTimestamp + 1;
  }

  /**
   * Reverses the calculation from {@link #registerRegularEndTimer(TimeWindow, Context)} to
   * calculate the state key for a given timer's time.
   *
   * @param windowMaxTime window.maxTimestamp() as used with the registered timer
   * @return state key (window end)
   */
  private long regularEndTimeToStateKey(long windowMaxTime) {
    return windowMaxTime + 1;
  }

  @Override
  public void open(Configuration parameters) throws Exception {
    super.open(parameters);
    watermark.init(getRuntimeContext());

    this.numLateRecordsDropped =
        getRuntimeContext().getMetricGroup().counter(LATE_ELEMENTS_DROPPED_METRIC_NAME);

    windowState =
        getRuntimeContext()
            .getMapState(
                new MapStateDescriptor<>(
                    "WindowAggregate",
                    Types.LONG,
                    Types.TUPLE(Types.LONG, windowAggregateFunction.getAccumulatorType())));

    timerState =
        getRuntimeContext()
            .getMapState(new MapStateDescriptor<>("WindowTimers", Types.LONG, Types.VOID));
  }

  private void setWindowContext(final Context ctx) {
    if (windowAssignerContext == null) {
      windowAssignerContext =
          new WindowAssignerContext() {
            @Override
            public long getCurrentProcessingTime() {
              return ctx.timerService().currentProcessingTime();
            }
          };
    }
  }

  @SuppressWarnings({"unused", "UnusedReturnValue"})
  public PerKeyWMAggregatingWindowWithProcessFunction<Key, IN, OUT, ACC, ACC_OUT> produces(
      TypeInformation<OUT> producedType) {
    this.producedType = producedType;
    return this;
  }

  @SuppressWarnings({"unused", "UnusedReturnValue"})
  public PerKeyWMAggregatingWindowWithProcessFunction<Key, IN, OUT, ACC, ACC_OUT> allowedLateness(
      Time lateness) {
    final long millis = lateness.toMilliseconds();
    checkArgument(millis >= 0, "The allowed lateness cannot be negative.");

    this.allowedLateness = millis;
    return this;
  }

  @SuppressWarnings({"unused", "UnusedReturnValue"})
  public PerKeyWMAggregatingWindowWithProcessFunction<Key, IN, OUT, ACC, ACC_OUT>
      sideOutputLateData(OutputTag<IN> outputTag) {
    Preconditions.checkNotNull(outputTag, "Side output tag must not be null.");
    this.lateDataOutputTag = outputTag;
    return this;
  }

  @SuppressWarnings({"unused", "UnusedReturnValue"})
  public PerKeyWMAggregatingWindowWithProcessFunction<Key, IN, OUT, ACC, ACC_OUT> triggerMode(
      TriggerResult windowFireMode) {
    checkArgument(
        windowFireMode == TriggerResult.FIRE || windowFireMode == TriggerResult.FIRE_AND_PURGE,
        "unsupported window fire mode: {}",
        windowFireMode);
    this.windowFireMode = windowFireMode;
    return this;
  }

  @Override
  public TypeInformation<OUT> getProducedType() {
    return producedType != null
        ? producedType
        : getProcessWindowFunctionReturnType(windowProcessFunction);
  }

  private static <IN, OUT, KEY> TypeInformation<OUT> getProcessWindowFunctionReturnType(
      ProcessWindowFunction<KEY, IN, OUT> function) {
    return TypeExtractor.getUnaryOperatorReturnType(
        function, ProcessWindowFunction.class, 1, 2, TypeExtractor.NO_INDEX, null, null, false);
  }
}
