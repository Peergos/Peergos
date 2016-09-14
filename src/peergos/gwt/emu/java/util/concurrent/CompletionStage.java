/*
 * Copyright 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package java.util.concurrent;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Emulation of CompletionStage.
 *
 */
public interface CompletionStage<T> {

  <U> CompletionStage<U> applyToEither(CompletionStage<? extends T> other,
      Function<? super T, U> fn);

  <U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> other,
      Function<? super T, U> fn);

  <U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> other,
      Function<? super T, U> fn, Executor executor);

  CompletionStage<Void> acceptEither(CompletionStage<? extends T> other, Consumer<? super T> action);

  CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> other,
      Consumer<? super T> action);

  CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> other,
      Consumer<? super T> action, Executor executor);

  CompletionStage<Void> thenAccept(Consumer<? super T> action);

  CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action);

  CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor);

  <U> CompletionStage<Void> thenAcceptBoth(CompletionStage<? extends U> other,
      BiConsumer<? super T, ? super U> action);

  <U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
      BiConsumer<? super T, ? super U> action);

  <U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
      BiConsumer<? super T, ? super U> action, Executor executor);

  <U> CompletionStage<U> thenApply(Function<? super T,? extends U> fn);

  <U> CompletionStage<U> thenApplyAsync(Function<? super T,? extends U> fn);

  <U> CompletionStage<U> thenApplyAsync(Function<? super T,? extends U> fn, Executor executor);

  <U,V> CompletionStage<V> thenCombine(CompletionStage<? extends U> other,
      BiFunction<? super T,? super U,? extends V> fn);

  <U,V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> other,
      BiFunction<? super T,? super U,? extends V> fn);

  <U,V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> other,
      BiFunction<? super T,? super U,? extends V> fn, Executor executor);

  <U> CompletionStage<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn);

  <U> CompletionStage<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn);

  <U> CompletionStage<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn,
      Executor executor);

  CompletionStage<Void> thenRun(Runnable action);

  CompletionStage<Void> thenRunAsync(Runnable action);

  CompletionStage<Void> thenRunAsync(Runnable action, Executor executor);

  CompletionStage<Void> runAfterBoth(CompletionStage<?> other, Runnable action);

  CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action);

  CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action, Executor executor);

  CompletionStage<Void> runAfterEither(CompletionStage<?> other, Runnable action);

  CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action);

  CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action,
      Executor executor);

  CompletionStage<T> whenComplete(BiConsumer<? super T, ? super Throwable> action);

  CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action);

  CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action,
      Executor executor);

  CompletionStage<T> exceptionally(Function<Throwable, ? extends T> fn);

  <U> CompletionStage<U> handle(BiFunction<? super T, Throwable, ? extends U> fn);

  <U> CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn);

  <U> CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn,
      Executor executor);

  CompletableFuture<T> toCompletableFuture();
}
