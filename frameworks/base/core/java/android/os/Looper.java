/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.LooperProto;
import android.util.Log;
import android.util.Printer;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

/**
  * Class used to run a message loop for a thread.  Threads by default do
  * not have a message loop associated with them; to create one, call
  * {@link #prepare} in the thread that is to run the loop, and then
  * {@link #loop} to have it process messages until the loop is stopped.
  *
  * <p>Most interaction with a message loop is through the
  * {@link Handler} class.
  *
  * <p>This is a typical example of the implementation of a Looper thread,
  * using the separation of {@link #prepare} and {@link #loop} to create an
  * initial Handler to communicate with the Looper.
  *
  * <pre>
  *  class LooperThread extends Thread {
  *      public Handler mHandler;
  *
  *      public void run() {
  *          Looper.prepare();
  *
  *          mHandler = new Handler() {
  *              public void handleMessage(Message msg) {
  *                  // process incoming messages here
  *              }
  *          };
  *
  *          Looper.loop();
  *      }
  *  }</pre>
  */
public final class Looper {
    /*
     * API Implementation Note:
     *
     * This class contains the code required to set up and manage an event loop
     * based on MessageQueue.  APIs that affect the state of the queue should be
     * defined on MessageQueue or Handler rather than on Looper itself.  For example,
     * idle handlers and sync barriers are defined on the queue whereas preparing the
     * thread, looping, and quitting are defined on the looper.
     */

    private static final String TAG = "Looper";

    // sThreadLocal.get() will return null unless you've called prepare().
    static final ThreadLocal<Looper> sThreadLocal = new ThreadLocal<Looper>();
    private static Looper sMainLooper;  // guarded by Looper.class

    final MessageQueue mQueue;
    final Thread mThread;

    private Printer mLogging;
    private long mTraceTag;

    /* If set, the looper will show a warning log if a message dispatch takes longer than time. */
    private long mSlowDispatchThresholdMs;

     /** Initialize the current thread as a looper.
      * This gives you a chance to create handlers that then reference
      * this looper, before actually starting the loop. Be sure to call
      * {@link #loop()} after calling this method, and end it by calling
      * {@link #quit()}.
      *
      * 初始化当前线程，使其作为一个Looper
      * 这样给了你一个机会在当前Looper实际开始loop前，去创建引用到当前Looper的Handler(可以多个)
      * 确保在调用这个方法后调用loop()方法，并通过调用quit()方法去结束
      */
    public static void prepare() {
        prepare(true);
    }

    private static void prepare(boolean quitAllowed) {
    	// 判断sThreadLocal是否为null，否则抛出异常
    	// 即：Looper.prepare()方法不能被调用两次，1个线程中只能对应1个Looper实例
    	// 注：sThreadLocal = 1个ThreadLocal对象，用于存储线程的变量
        if (sThreadLocal.get() != null) {
            throw new RuntimeException("Only one Looper may be created per thread");
        }
		// 若为初次调用Looper.prepare()，则创建Looper对象 & 存放在ThreadLocal变量中
		// Looper对象是存放在Thread线程里的
        sThreadLocal.set(new Looper(quitAllowed));
    }

    /**
     * Initialize the current thread as a looper, marking it as an
     * application's main looper. The main looper for your application
     * is created by the Android environment, so you should never need
     * to call this function yourself.  See also: {@link #prepare()}
     *
     * 初始化当前线程，使其作为一个Looper，并标记为当前应用的主Looper
     * 你的应用的主Looper由Android环境创建，所以你不需要亲自调用这个方法
     *
     * 在Android应用进程启动时，会默认创建1个主线程(ActivityThread，也叫UI线程)
     * 创建ActivityThread时，会调用其静态的main()方法 = 应用程序的入口
     * main()方法内部则会调用Looper.prepareMainLooper()为主线程生成1个Looper对象
     */
    public static void prepareMainLooper() {
        prepare(false);
        synchronized (Looper.class) {
            if (sMainLooper != null) {
                throw new IllegalStateException("The main Looper has already been prepared.");
            }
            sMainLooper = myLooper();
        }
    }

    /**
     * Returns the application's main looper, which lives in the main thread of the application.
     */
    public static Looper getMainLooper() {
        synchronized (Looper.class) {
            return sMainLooper;
        }
    }

    /**
     * Run the message queue in this thread. Be sure to call
     * {@link #quit()} to end the loop.
     *
     * 运行当前线程的消息队列。确认在结束轮训消息队列是调用quit()方法
     */
    public static void loop() {
    	// 获取当前线程的Looper，如果未获取到则抛出异常
        final Looper me = myLooper();
        if (me == null) {
            throw new RuntimeException("No Looper; Looper.prepare() wasn't called on this thread.");
        }
		// 获取消息队列对象
        final MessageQueue queue = me.mQueue;

        // Make sure the identity of this thread is that of the local process,
        // and keep track of what that identity token actually is.
        Binder.clearCallingIdentity();
        final long ident = Binder.clearCallingIdentity();

        for (;;) {
			// 从消息队列中取出消息，若取出的消息为空，则会阻塞线程
            Message msg = queue.next(); // might block
            if (msg == null) {
                // No message indicates that the message queue is quitting.
                return;
            }

            // This must be in a local variable, in case a UI event sets the logger
            final Printer logging = me.mLogging;
            if (logging != null) {
                logging.println(">>>>> Dispatching to " + msg.target + " " +
                        msg.callback + ": " + msg.what);
            }

            final long slowDispatchThresholdMs = me.mSlowDispatchThresholdMs;

            final long traceTag = me.mTraceTag;
            if (traceTag != 0 && Trace.isTagEnabled(traceTag)) {
                Trace.traceBegin(traceTag, msg.target.getTraceName(msg));
            }
            final long start = (slowDispatchThresholdMs == 0) ? 0 : SystemClock.uptimeMillis();
            final long end;
            try {
				// 把消息Message派发给消息对象msg的target属性，target实际是一个Handler对象
                msg.target.dispatchMessage(msg);
                end = (slowDispatchThresholdMs == 0) ? 0 : SystemClock.uptimeMillis();
            } finally {
                if (traceTag != 0) {
                    Trace.traceEnd(traceTag);
                }
            }
            if (slowDispatchThresholdMs > 0) {
                final long time = end - start;
                if (time > slowDispatchThresholdMs) {
                    Slog.w(TAG, "Dispatch took " + time + "ms on "
                            + Thread.currentThread().getName() + ", h=" +
                            msg.target + " cb=" + msg.callback + " msg=" + msg.what);
                }
            }

            if (logging != null) {
                logging.println("<<<<< Finished to " + msg.target + " " + msg.callback);
            }

            // Make sure that during the course of dispatching the
            // identity of the thread wasn't corrupted.
            final long newIdent = Binder.clearCallingIdentity();
            if (ident != newIdent) {
                Log.wtf(TAG, "Thread identity changed from 0x"
                        + Long.toHexString(ident) + " to 0x"
                        + Long.toHexString(newIdent) + " while dispatching to "
                        + msg.target.getClass().getName() + " "
                        + msg.callback + " what=" + msg.what);
            }

			// 释放消息占据的资源
            msg.recycleUnchecked();
        }
    }

    /**
     * Return the Looper object associated with the current thread.  Returns
     * null if the calling thread is not associated with a Looper.
     *
     * 返回关联当前线程的Looper对象。如果调用的线程没有关联Looper则返回null
     */
    public static @Nullable Looper myLooper() {
        return sThreadLocal.get();
    }

    /**
     * Return the {@link MessageQueue} object associated with the current
     * thread.  This must be called from a thread running a Looper, or a
     * NullPointerException will be thrown.
     */
    public static @NonNull MessageQueue myQueue() {
        return myLooper().mQueue;
    }

    private Looper(boolean quitAllowed) {
    	// 创建1个消息队列对象(MessageQueue)
    	// 即：当创建1个Looper实例时，会自动创建一个与之匹配的消息队列对象(MessageQueue)
        mQueue = new MessageQueue(quitAllowed);
        mThread = Thread.currentThread();
    }

    /**
     * Returns true if the current thread is this looper's thread.
     */
    public boolean isCurrentThread() {
        return Thread.currentThread() == mThread;
    }

    /**
     * Control logging of messages as they are processed by this Looper.  If
     * enabled, a log message will be written to <var>printer</var>
     * at the beginning and ending of each message dispatch, identifying the
     * target Handler and message contents.
     *
     * @param printer A Printer object that will receive log messages, or
     * null to disable message logging.
     */
    public void setMessageLogging(@Nullable Printer printer) {
        mLogging = printer;
    }

    /** {@hide} */
    public void setTraceTag(long traceTag) {
        mTraceTag = traceTag;
    }

    /** {@hide} */
    public void setSlowDispatchThresholdMs(long slowDispatchThresholdMs) {
        mSlowDispatchThresholdMs = slowDispatchThresholdMs;
    }

    /**
     * Quits the looper.
     * <p>
     * Causes the {@link #loop} method to terminate without processing any
     * more messages in the message queue.
     * </p><p>
     * Any attempt to post messages to the queue after the looper is asked to quit will fail.
     * For example, the {@link Handler#sendMessage(Message)} method will return false.
     * </p><p class="note">
     * Using this method may be unsafe because some messages may not be delivered
     * before the looper terminates.  Consider using {@link #quitSafely} instead to ensure
     * that all pending work is completed in an orderly manner.
     * </p>
     *
     * @see #quitSafely
     */
    public void quit() {
        mQueue.quit(false);
    }

    /**
     * Quits the looper safely.
     * <p>
     * Causes the {@link #loop} method to terminate as soon as all remaining messages
     * in the message queue that are already due to be delivered have been handled.
     * However pending delayed messages with due times in the future will not be
     * delivered before the loop terminates.
     * </p><p>
     * Any attempt to post messages to the queue after the looper is asked to quit will fail.
     * For example, the {@link Handler#sendMessage(Message)} method will return false.
     * </p>
     */
    public void quitSafely() {
        mQueue.quit(true);
    }

    /**
     * Gets the Thread associated with this Looper.
     *
     * @return The looper's thread.
     */
    public @NonNull Thread getThread() {
        return mThread;
    }

    /**
     * Gets this looper's message queue.
     *
     * @return The looper's message queue.
     */
    public @NonNull MessageQueue getQueue() {
        return mQueue;
    }

    /**
     * Dumps the state of the looper for debugging purposes.
     *
     * @param pw A printer to receive the contents of the dump.
     * @param prefix A prefix to prepend to each line which is printed.
     */
    public void dump(@NonNull Printer pw, @NonNull String prefix) {
        pw.println(prefix + toString());
        mQueue.dump(pw, prefix + "  ", null);
    }

    /**
     * Dumps the state of the looper for debugging purposes.
     *
     * @param pw A printer to receive the contents of the dump.
     * @param prefix A prefix to prepend to each line which is printed.
     * @param handler Only dump messages for this Handler.
     * @hide
     */
    public void dump(@NonNull Printer pw, @NonNull String prefix, Handler handler) {
        pw.println(prefix + toString());
        mQueue.dump(pw, prefix + "  ", handler);
    }

    /** @hide */
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long looperToken = proto.start(fieldId);
        proto.write(LooperProto.THREAD_NAME, mThread.getName());
        proto.write(LooperProto.THREAD_ID, mThread.getId());
        proto.write(LooperProto.IDENTITY_HASH_CODE, System.identityHashCode(this));
        mQueue.writeToProto(proto, LooperProto.QUEUE);
        proto.end(looperToken);
    }

    @Override
    public String toString() {
        return "Looper (" + mThread.getName() + ", tid " + mThread.getId()
                + ") {" + Integer.toHexString(System.identityHashCode(this)) + "}";
    }
}
