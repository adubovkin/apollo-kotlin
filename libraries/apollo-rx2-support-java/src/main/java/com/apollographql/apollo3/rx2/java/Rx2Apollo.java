package com.apollographql.apollo3.rx2.java;

import com.apollographql.apollo3.api.ApolloResponse;
import com.apollographql.apollo3.api.Operation;
import com.apollographql.apollo3.exception.ApolloException;
import com.apollographql.apollo3.runtime.java.ApolloCall;
import com.apollographql.apollo3.runtime.java.ApolloCallback;
import com.apollographql.apollo3.runtime.java.ApolloDisposable;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.annotations.CheckReturnValue;
import io.reactivex.disposables.Disposable;
import org.jetbrains.annotations.NotNull;

import static com.apollographql.apollo3.api.java.Assertions.checkNotNull;

public class Rx2Apollo {
  @NotNull
  @CheckReturnValue
  public static <T extends Operation.Data> Flowable<ApolloResponse<T>> flowable(@NotNull final ApolloCall<T> call, @NotNull BackpressureStrategy backpressureStrategy) {
    checkNotNull(call, "originalCall == null");
    checkNotNull(backpressureStrategy, "backpressureStrategy == null");
    return Flowable.create(emitter -> {
      ApolloDisposable disposable = call.enqueue(new ApolloCallback<T>() {

        @Override public void onResponse(@NotNull ApolloResponse<T> response) {
          if (!emitter.isCancelled()) {
            emitter.onNext(response);
          }
        }

        @Override public void onFailure(@NotNull ApolloException e) {
          if (!emitter.isCancelled()) {
            emitter.onError(e);
          }
        }
      });

      disposable.addListener(() -> {
        if (!emitter.isCancelled()) {
          emitter.onComplete();
        }
      });

      emitter.setDisposable(new Disposable() {
        @Override public void dispose() {
          disposable.dispose();
        }

        @Override public boolean isDisposed() {
          return disposable.isDisposed();
        }
      });
    }, backpressureStrategy);
  }

  @NotNull
  @CheckReturnValue
  public static <T extends Operation.Data> Flowable<ApolloResponse<T>> flowable(@NotNull final ApolloCall<T> call) {
    return flowable(call, BackpressureStrategy.BUFFER);
  }

  @NotNull
  @CheckReturnValue
  public static <T extends Operation.Data> Single<ApolloResponse<T>> single(@NotNull final ApolloCall<T> call, @NotNull BackpressureStrategy backpressureStrategy) {
    return flowable(call, backpressureStrategy).firstOrError();
  }

  @NotNull
  @CheckReturnValue
  public static <T extends Operation.Data> Single<ApolloResponse<T>> single(@NotNull final ApolloCall<T> call) {
    return single(call, BackpressureStrategy.BUFFER);
  }
}
