package gr.uoa.di.promise;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/*
 * > "What I cannot create, I do not understand"
 * > Richard Feynman
 * > https://en.wikipedia.org/wiki/Richard_Feynman
 * 
 * This is an incomplete implementation of the Javascript Promise machinery in Java.
 * You should expand and ultimately complete it according to the following:
 * 
 * (1) You should only use the low-level Java concurrency primitives (like 
 * java.lang.Thread/Runnable, wait/notify, synchronized, volatile, etc)
 * in your implementation. 
 * 
 * (2) The members of the java.util.concurrent package 
 * (such as Executor, Future, CompletableFuture, etc.) cannot be used.
 * 
 * (3) No other library should be used.
 * 
 * (4) Create as many threads as you think appropriate and don't worry about
 * recycling them or implementing a Thread Pool.
 * 
 * (5) I may have missed something from the spec, so please report any issues
 * in the course's e-class.
 * 
 * The Javascript Promise reference is here:
 * https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Promise
 * 
 * A helpful guide to help you understand Promises is available here:
 * https://javascript.info/async
 */
public class Promise<V> {
    private final PromiseExecutor<V> noop = (onResolve, onReject) -> {};

    public static enum Status {
        PENDING,
        FULFILLED,
        REJECTED,
        WAIT_PROMISE
    }

    Status PENDING = Status.PENDING;
    Status FULFILLED = Status.FULFILLED;
    Status REJECTED = Status.REJECTED;
    Status WAIT_PROMISE = Status.WAIT_PROMISE;

    public void setState(Status state) {
        this.state = state;
    }

    Status state;

    Status deferredState;

    public void setvOrE(ValueOrError<?> vOrE) {
        this.vOrE = vOrE;
    }

    public ValueOrError<?> getvOrE() {
        return vOrE;
    }

    ValueOrError<?> vOrE;

    List<DeferredPromise<V>> deferreds = new ArrayList<>();

    List<DeferredPromiseFinally<V>> deferredsFinally = new ArrayList<>();

    public Promise(PromiseExecutor<V> executor) {
        this.state = PENDING;
        vOrE = null;
        this.deferredState = PENDING;
        doResolve(this, executor);
    }

    public boolean isPending() {
        return this.state == PENDING;
    }

    public boolean isRejected() {
        return this.state == REJECTED;
    }

    public boolean isFulfilled() {
        return this.state == FULFILLED;
    }


    private void doResolve(Promise<V> self,PromiseExecutor<V> action) {

        final boolean[] done = {false};
        final Consumer<V> resolve =  value -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            onResolve(self, value);
        };

        final Consumer<Throwable> reject = reason -> {
            if(done[0]) {
                return;
            }
            done[0] = true;
            onReject(self, reason);
        };

        try {
            action.execute(resolve, reject);
        } catch (Exception e) {
            done[0] = true;
            onReject(self,e);
        }
    }

    private void onResolve(Promise<V> self, V newValue) {
        try {
            if (newValue == self) {
                onReject(self,new IllegalArgumentException("A promise cannot be resolved with itself."));
                throw new IllegalArgumentException("A promise cannot be resolved with itself.");
            }
            if (self.state == REJECTED) {
                onReject(self, (Throwable) newValue);
                return;
            }
            self.state = (newValue instanceof Promise) ? WAIT_PROMISE : FULFILLED;
            self.vOrE = ValueOrError.Value.of(newValue);
            finale(self);
        } catch (Exception e) {
            onReject(self,e);
        }
    }

    private void onReject(Promise<V> self, Throwable newValue) {
        self.state = REJECTED;
        self.vOrE = ValueOrError.Error.of(newValue);
        finale(self);
    }

    private void finale(Promise<V> self) {

        for (DeferredPromise<V> deferred : self.deferreds) {
            handle(self,deferred);
        }
        self.deferreds.clear();
    }

    //deferreds are the pending promises
    //deferreds are the pending promises
    //deferreds are the pending promises
    private void handle(Promise<V> self, DeferredPromise<V> deferred) {

        while (self.state == WAIT_PROMISE) {
            self = (Promise<V>) self.vOrE;
        }

        if (self.isPending()) {
            self.deferreds.add(deferred);
            return;
        }

        Function<V,V> callback = self.isFulfilled() ? deferred.onFulfilled : adapt(deferred.onRejected);
        if (callback != null) {
            try {
                if (self.isFulfilled()) {
                    onResolve(deferred.promise, callback.apply((V) self.vOrE.value()));
                } else {
                    onReject(deferred.promise, (Throwable) callback.apply((V) self.vOrE.error()));
                }
            } catch (Exception e) {
                onReject(deferred.promise, self.vOrE.error());
            }
        } else if (self.isFulfilled()) {
            onResolve(deferred.promise, (V) self.vOrE.value());
        } else {
            onReject(deferred.promise, self.vOrE.error());
        }
    }


    public <T> Function<V,T> adapt(final Consumer<Throwable> consumer) {
        return t -> {
            consumer.accept((Throwable) t);
            return null;
        };
    }

    public <T> Promise<T> then(Function<V, T> onResolve, Consumer<Throwable> onReject) {
        Promise<V> promise = new Promise<>(noop);
        promise.setvOrE(this.vOrE);
        promise.setState(this.state);
        handle(this, new DeferredPromise<V>(promise, (Function<V, V>) onResolve, onReject));
        return (Promise<T>) promise;
    }

    public <T> Promise<T> then(Function<V, T> onResolve) {
        return (Promise<T>) this.then(onResolve, null);
    }

    // catch is a reserved word in Java.
    public Promise<?> catchError(Consumer<Throwable> onReject) {
        return this.then(null, onReject);
    }
    
    // finally is a reserved word in Java.
    public Promise<V> andFinally(Consumer<ValueOrError<V>> onSettle) {
        if (vOrE != null) {
            onSettle.accept((ValueOrError<V>) vOrE);
        }
        return this.thenFinally(onSettle);

    }

    public Promise<V> thenFinally(Consumer<ValueOrError<V>> onSettle) {
        Promise<V> promise = new Promise<>(noop);
        promise.setvOrE(this.vOrE);
        promise.setState(this.state);
        handleFinally(this, new DeferredPromiseFinally<V>(promise, onSettle));
        return promise;
    }

    private void handleFinally(Promise<V> self, DeferredPromiseFinally<V> deferred) {

        while (self.state == WAIT_PROMISE) {
            self = (Promise<V>) self.vOrE;
        }

        if (self.isPending()) {
            self.deferredsFinally.add(deferred);
            return;
        }

        Function <V,V> callback = adaptFinally(deferred.onSettle);
        if (callback != null) {
            try {
                if (self.isFulfilled()) {

                    onResolve(deferred.promise, (V) self.vOrE.value());
                } else {
                    onReject(deferred.promise, (Throwable) callback.apply((V) self.vOrE.error()));
                }
            } catch (Exception e) {
                onReject(deferred.promise, e);
            }
        } else if (self.isFulfilled()) {
            onResolve(deferred.promise, (V) self.vOrE.value());
        } else {
            onReject(deferred.promise, self.vOrE.error());
        }
    }

    public <T> Function<V,T> adaptFinally(final Consumer<ValueOrError<V>> consumer) {
        return t -> {
            consumer.accept((ValueOrError<V>) t);
            return null;
        };
    }


    public static <T> Promise<T> resolve(T value) {
        return new Promise<>((onResolve, onReject) -> onResolve.accept(value));
    }

    public static Promise<Void> reject(Throwable error) {
        return new Promise<>((onResolve, onReject) -> onReject.accept(error));
    }

    public static Promise<ValueOrError<?>> race(List<Promise<?>> promises) {
        return new Promise<>((resolve, reject) -> {
            for (Promise<?> promise: promises) {
                promise.then(value -> {
                    ValueOrError<?> vOrE = ValueOrError.Value.of(value);
                    resolve.accept(vOrE);
                    return value;
                }).catchError(err -> {
                    ValueOrError<?> vOrE = ValueOrError.Error.of(err);
                    reject.accept((Throwable) vOrE);

                });
            }
        });
    }


    public static Promise<?> any(List<Promise<?>> promises) {
        return new Promise<>((resolve, reject) -> {
            for (Promise<?> promise : promises) {
                if (promise.isRejected()) {
                    continue;
                }
                if (promise.isFulfilled()) {
                    promise.then(value -> {
                        resolve.accept(value);
                        return value;
                    });
                }
            }
        });
    }

    public static Promise<List<?>> all(List<Promise<?>> promises) {
        return new Promise<>((resolve, reject) -> {
            final int size = promises.size();
            int[] remaining = {size};
            final Object[] error = {new Object()};
            final boolean[] foundRejected = {false};
            Object[] result = new Object[size];

            for (int i = 0; i < size; i++) {
                final int index = i;
                promises.get(i).then(value -> {
                    if (foundRejected[0]) {
                        return value;
                    }
                    result[index] = value;
                    remaining[0]--;
                    if (remaining[0] == 0) {
                        resolve.accept(Arrays.asList(result));
                    }
                    return value;
                }).catchError(err -> {
                    if (!foundRejected[0]) {
                        error[0] = err;
                        foundRejected[0] = true;
                        reject.accept((Throwable) error[0]);
                    }
                });
            }
        });
    }

    public static Promise<List<ValueOrError<?>>> allSettled(List<Promise<?>> promises) {
        return new Promise<>((resolve, reject) -> {
            final int size = promises.size();
            int[] remaining = {size};
            List<ValueOrError<?>> result = new ArrayList<>();

            for (Promise<?> promise : promises) {
                promise.then(value -> {
                    ValueOrError<?> vOrE = ValueOrError.Value.of(value);
                    result.add(vOrE);
                    remaining[0]--;
                    if (remaining[0] == 0) {
                        resolve.accept(result);
                    }
                    return value;
                }).catchError(err -> {
                    ValueOrError<?> vOrE = ValueOrError.Error.of(err);
                    result.add(vOrE);
                    remaining[0]--;

                });
            }
        });
    }

}
