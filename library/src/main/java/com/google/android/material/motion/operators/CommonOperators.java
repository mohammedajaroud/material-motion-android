/*
 * Copyright 2017-present The Material Motion Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.material.motion.operators;

import android.os.Handler;
import android.os.Looper;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.v4.util.SimpleArrayMap;
import android.util.Log;

import com.google.android.indefinite.observable.IndefiniteObservable.Subscription;
import com.google.android.material.motion.FilterOperation;
import com.google.android.material.motion.MapOperation;
import com.google.android.material.motion.MotionObservable;
import com.google.android.material.motion.MotionObserver;
import com.google.android.material.motion.Operation;
import com.google.android.material.motion.RawOperation;
import com.google.android.material.motion.SlopEvent;
import com.google.android.material.motion.ThresholdSide;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

public class CommonOperators {

  private static final SimpleArrayMap<Integer, Integer> THRESHOLD_SIDE_TO_SLOP_EVENT_MAP =
    new SimpleArrayMap<>();

  static {
    THRESHOLD_SIDE_TO_SLOP_EVENT_MAP.put(ThresholdSide.BELOW, SlopEvent.EXIT);
    THRESHOLD_SIDE_TO_SLOP_EVENT_MAP.put(ThresholdSide.WITHIN, SlopEvent.RETURN);
    THRESHOLD_SIDE_TO_SLOP_EVENT_MAP.put(ThresholdSide.ABOVE, SlopEvent.EXIT);
  }

  @VisibleForTesting
  public CommonOperators() {
    throw new UnsupportedOperationException();
  }

  public static <T> Operation<T, T> dedupe() {
    return new Operation<T, T>() {

      private boolean dispatched;
      @Nullable
      private T lastValue;

      @Override
      public void next(MotionObserver<T> observer, T value) {
        if (dispatched && lastValue == value) {
          return;
        }

        lastValue = value;
        dispatched = true;

        observer.next(value);
      }
    };
  }

  public static <T> Operation<T, T> ignoreUntil(final T expected) {
    return new FilterOperation<T>() {

      private boolean received;

      @Override
      public boolean filter(T value) {
        if (expected.equals(value)) {
          received = true;
        }

        return received;
      }
    };
  }

  public static <T> Operation<T, T> log(final String tag) {
    return log(tag, "");
  }

  public static <T> Operation<T, T> log(final String tag, final String prefix) {
    return log(Log.DEBUG, tag, prefix);
  }

  public static <T> Operation<T, T> log(final int priority, final String tag, final String prefix) {
    return new Operation<T, T>() {
      @Override
      public void next(MotionObserver<T> observer, T value) {
        Log.println(priority, tag, prefix + value);
        observer.next(value);
      }
    };
  }

  public static <T extends Comparable<T>> Operation<T, Integer> threshold(final T threshold) {
    return thresholdRange(threshold, threshold);
  }

  public static <T extends Comparable<T>> Operation<T, Integer> thresholdRange(
    final T min, final T max) {
    return new Operation<T, Integer>() {
      @Override
      public void next(MotionObserver<Integer> observer, T value) {
        if (min.compareTo(max) > 0) {
          return;
        }

        if (min.compareTo(value) > 0) {
          observer.next(ThresholdSide.BELOW);
        } else if (max.compareTo(value) < 0) {
          observer.next(ThresholdSide.ABOVE);
        } else {
          observer.next(ThresholdSide.WITHIN);
        }
      }
    };
  }

  public static <T extends Comparable<T>> RawOperation<T, Integer> slop(final T min, final T max) {
    return new RawOperation<T, Integer>() {
      @Override
      public MotionObservable<Integer> compose(MotionObservable<? extends T> stream) {
        return stream
          .compose(thresholdRange(min, max))
          .compose(rewrite(THRESHOLD_SIDE_TO_SLOP_EVENT_MAP))
          .compose(CommonOperators.<Integer>dedupe())
          .compose(ignoreUntil(SlopEvent.EXIT));
      }
    };
  }

  public static <T> Operation<T, T> merge(final MotionObservable<T> stream) {
    return new Operation<T, T>() {

      private Subscription subscription;

      @Override
      public void next(MotionObserver<T> observer, T value) {
        observer.next(value);
      }

      @Override
      public void postConnect(MotionObserver<T> observer) {
        subscription = stream.subscribe(observer);
      }

      @Override
      public void preDisconnect(MotionObserver<T> observer) {
        subscription.unsubscribe();
      }
    };
  }

  public static <T, U> Operation<T, U> rewriteTo(final U value) {
    return new MapOperation<T, U>() {
      @Override
      public U transform(T ignored) {
        return value;
      }
    };
  }

  public static <T, U> Operation<T, U> rewrite(final Map<T, U> map) {
    return new Operation<T, U>() {
      @Override
      public void next(MotionObserver<U> observer, T value) {
        if (map.containsKey(value)) {
          observer.next(map.get(value));
        }
      }
    };
  }

  public static <T, U> Operation<T, U> rewrite(final SimpleArrayMap<T, U> map) {
    return new Operation<T, U>() {
      @Override
      public void next(MotionObserver<U> observer, T value) {
        if (map.containsKey(value)) {
          observer.next(map.get(value));
        }
      }
    };
  }

  public static <T> Operation<T, T> delayBy(final long delayMillis) {
    return new Operation<T, T>() {

      private final Handler handler = new Handler(Looper.getMainLooper());
      private SimpleArrayMap<MotionObserver<T>, Deque<Runnable>> runnables = new SimpleArrayMap<>();

      @Override
      public void preConnect(MotionObserver<T> observer) {
        if (!runnables.containsKey(observer)) {
          runnables.put(observer, new ArrayDeque<Runnable>());
        }
      }

      @Override
      public void next(final MotionObserver<T> observer, final T value) {
        final Runnable runnable = new Runnable() {
          @Override
          public void run() {
            runnables.get(observer).remove(this);
            observer.next(value);
          }
        };

        runnables.get(observer).add(runnable);
        handler.postDelayed(runnable, delayMillis);
      }

      @Override
      public void preDisconnect(MotionObserver<T> observer) {
        while (!runnables.get(observer).isEmpty()) {
          handler.removeCallbacks(runnables.get(observer).poll());
        }
      }
    };
  }
}
