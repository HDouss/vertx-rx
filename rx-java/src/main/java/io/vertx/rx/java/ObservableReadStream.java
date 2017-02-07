package io.vertx.rx.java;

import io.vertx.core.streams.ReadStream;
import rx.Observable;
import rx.Producer;
import rx.Subscriber;
import rx.Subscription;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class ObservableReadStream<T, R> implements Observable.OnSubscribe<R>, Adapter<T> {

  private static <T> T noItem() {
    return (T) NO_ITEM;
  }

  private static final Object NO_ITEM = new Object();
  public static final long DEFAULT_MAX_BUFFER_SIZE = 256;

  private final long highWaterMark;
  private final ReadStream<T> stream;
  private final Function<T, R> adapter;
  private final AtomicReference<Sub> subscription = new AtomicReference<>();
  private Throwable completed;
  private long requested;

  public ObservableReadStream(ReadStream<T> stream, Function<T, R> adapter) {
    this(stream, adapter, DEFAULT_MAX_BUFFER_SIZE);
  }

  public ObservableReadStream(ReadStream<T> stream, Function<T, R> adapter, long maxBufferSize) {
    this.stream = stream;
    this.adapter = adapter;
    this.highWaterMark = maxBufferSize;
  }

  public long getRequested() {
    Sub sub = subscription.get();
    return sub != null ? sub.queue.requested() : 0;
  }

  private class Sub implements Subscription, Producer {

    final Subscriber<? super R> subscriber;
    Adapter<T> queue = ObservableReadStream.this;

    Sub(Subscriber<? super R> subscriber) {
      this.subscriber = subscriber;
    }

    @Override
    public void request(long n) {
      if (n < 0) {
        throw new IllegalArgumentException("Cannot request negative items:" + n);
      }
      queue.request(n);
    }

    @Override
    public void unsubscribe() {
      if (subscription.compareAndSet(this, null)) {
        queue.dispose();
        RxHelper.setNullHandlers(stream);
      }
    }

    @Override
    public boolean isUnsubscribed() {
      return subscription.get() != this;
    }
  }

  @Override
  public void call(Subscriber<? super R> subscriber) {
    Sub sub = new Sub(subscriber);
    if (!subscription.compareAndSet(null, sub)) {
      throw new IllegalStateException();
    }
    subscriber.setProducer(sub);
    subscriber.add(sub);
    if (requested != Long.MAX_VALUE) {
      sub.queue = new QueueAdapter(requested);
    }

    // At this moment reactive back-pressure should have been established (or not)
    // so we can pass set the handlers as they won't change
    stream.exceptionHandler(sub.queue::end);
    stream.endHandler(v -> sub.queue.end(COMPLETED_SENTINEL));
    stream.handler(sub.queue);
  }

  @Override
  public void end(Throwable t) {
    Sub sub = subscription.get();
    if (sub != null) {
      if (t == COMPLETED_SENTINEL) {
        sub.subscriber.onCompleted();
      } else {
        sub.subscriber.onError(t);
      }
    }
  }

  @Override
  public void handle(T item) {
    Sub sub = subscription.get();
    if (sub != null) {
      sub.subscriber.onNext(adapter.apply(item));
    }
  }

  @Override
  public long requested() {
    return requested;
  }

  @Override
  public void request(long n) {
    if (n == Long.MAX_VALUE || (n >= Long.MAX_VALUE - requested)) {
      requested = Long.MAX_VALUE;
    } else {
      requested += n;
    }
  }

  private class QueueAdapter implements Adapter<T> {

    private static final int NOOP = 0, RESUME = 1, PAUSE = 2;

    private long requested;
    private final long lowWaterMark;
    private ArrayDeque<R> pending = new ArrayDeque<>();
    private boolean draining;
    private boolean paused;

    private QueueAdapter(long requested) {
      this.lowWaterMark = highWaterMark / 2;
      this.requested = requested;
    }

    @Override
    public synchronized long requested() {
      return requested;
    }

    @Override
    public void dispose() {
      boolean resume;
      synchronized (this) {
        resume = paused;
        paused = false;
      }
      if (resume) {
        stream.resume();
      }
    }

    @Override
    public void request(long n) {
      synchronized (this) {
        if (n == Long.MAX_VALUE || (n >= Long.MAX_VALUE - requested)) {
          requested = Long.MAX_VALUE;
        } else {
          requested += n;
        }
      }
      drain();
    }

    private void drain() {
      synchronized (this) {
        if (draining) {
          return;
        }
        draining = true;
      }
      Sub sub;
      while (true) {
        sub = subscription.get();
        int action = NOOP;
        R next = noItem();
        synchronized (this) {
          if (sub == null) {
            draining = false;
            return;
          } else if (pending.size() > 0) {
            if (requested > 0) {
              next = pending.poll();
              if (requested != Long.MAX_VALUE) {
                requested--;
              }
            }
          } else {
            if (completed != null) {
              break;
            }
          }
          if (paused && pending.size() < lowWaterMark) {
            paused = false;
            action = RESUME;
          } else
          if (!paused && pending.size() >= highWaterMark) {
            paused = true;
            action = PAUSE;
          }
        }
        switch (action) {
          case RESUME:
            stream.resume();
            break;
          case PAUSE:
            stream.pause();
            break;
        }
        if (next != NO_ITEM) {
          sub.subscriber.onNext(next);
        } else {
          break;
        }
      }
      synchronized (this) {
        if (pending.size() == 0 && completed != null) {
          if (completed == COMPLETED_SENTINEL) {
            sub.subscriber.onCompleted();
          } else {
            sub.subscriber.onError(completed);
          }
        }
        draining = false;
      }
    }

    @Override
    public void handle(T item) {
      synchronized (this) {
        pending.add(adapter.apply(item));
      }
      drain();
    }

    public void end(Throwable t) {
      synchronized (ObservableReadStream.this) {
        if (completed != null) {
          return;
        }
        completed = t;
      }
      drain();
    }
  }
}
