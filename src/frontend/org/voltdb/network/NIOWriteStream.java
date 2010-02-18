/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.network;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.SelectionKey;
import java.util.ArrayDeque;

import org.apache.log4j.Logger;
import org.voltdb.messaging.FastSerializable;
import org.voltdb.messaging.FastSerializer;
import org.voltdb.utils.DBBPool;
import org.voltdb.utils.DeferredSerialization;
import org.voltdb.utils.EstTime;
import org.voltdb.utils.VoltLoggerFactory;
import org.voltdb.utils.DBBPool.BBContainer;

/**
*
*  Provide a queue for ByteBuffers and DeferredSerializations and drain them to gathering ByteChannel.
*  Uses a thread local memory pool for serializing messages that are < MAX_GATHERING_WRITE size and HeapByteBuffers
*  otherwise. Jumps through serious hoops to avoid ever writing large HeapByteBuffers to the channel
*  because Java will allocate a DirectByteBuffer and copy ALL the data into the DirectByteBuffer even if only
*  a small fraction can reasonably be written to the channel. This wastes time in copying data that can never possibly
*  make it into the channel in non blocking mode and space because the DirectByteBuffer is never released unlike
*  the pool which shrinks after a long time without usage.
*
*  The value m_port.m_expectedOutgoingMessageSize is used to set the initial storage a FastSerializer will
*  allocate for when doing deferred serialization of FastSerializables. FastSerializable + enqueue is the
*  best way to serialize data unless you can't pick a good value for m_port.m_expectedOutgoingMessageSize.
*  In most cases you are optimizing for the bulk of your message and it is fine to guess a little high as the memory
*  allocation works well.
*/
public class NIOWriteStream implements WriteStream {

    /**
     * Reference to the port for changing interest ops
     */
    private final VoltPort m_port;

    private static final Logger networkLog = Logger.getLogger("NETWORK", VoltLoggerFactory.instance());

    private boolean m_isShutdown = false;

    /**
     * Contains serialized buffers ready to write to the socket
     */
    private final ArrayDeque<BBContainer> m_queuedBuffers = new ArrayDeque<BBContainer>();

    /**
     * Contains messages waiting to be serialized and written to the socket
     */
    private final ArrayDeque<DeferredSerialization> m_queuedWrites1 =
        new ArrayDeque<DeferredSerialization>();

    private final ArrayDeque<DeferredSerialization> m_queuedWrites2 =
        new ArrayDeque<DeferredSerialization>();

    private ArrayDeque<DeferredSerialization> m_queuedWrites = m_queuedWrites1;

    private final Runnable m_offBackPressureCallback;
    private final Runnable m_onBackPressureCallback;

    private final QueueMonitor m_monitor;

    private long m_bytesWritten = 0;

    synchronized long getBytesWritten() { return m_bytesWritten; }

    /**
     * Set to -1 when there are no pending writes. If there is a pending write it is set to the time
     * of the last successful write or the time the oldest pending write was queued.
     */
    private long m_lastPendingWriteTime = -1;

    NIOWriteStream(VoltPort port) {
        this(port, null, null, null);
    }

    NIOWriteStream (
            VoltPort port,
            Runnable offBackPressureCallback,
            Runnable onBackPressureCallback,
            QueueMonitor monitor)
    {
        m_port = port;
        m_offBackPressureCallback = offBackPressureCallback;
        m_onBackPressureCallback = onBackPressureCallback;
        m_monitor = monitor;

    }

    synchronized public boolean isEmpty()
    {
        return m_queuedBuffers.isEmpty() && m_queuedWrites.isEmpty();
    }

    /**
     * Returns true when a drainTo invocation was unable to completely drain all queued bytes
     */
    @Override
    public boolean hadBackPressure() {
        return m_hadBackPressure;
    }

    /**
     * Called when not all queued data could be flushed to the channel
     */
    private final void backpressureStarted() {
        if (networkLog.isTraceEnabled()) {
            networkLog.trace("Backpressure started for client " + m_port);
        }
        m_hadBackPressure = true;
        if (m_onBackPressureCallback != null) {
            m_onBackPressureCallback.run();
        }
    }

    /**
     * Called when all queued data is flushed to the channel
     */
    private final void backpressureEnded() {
        if (networkLog.isTraceEnabled()) {
            networkLog.trace("Backpressure ended for client " + m_port);
        }
        m_hadBackPressure = false;
        if (m_offBackPressureCallback != null) {
            m_offBackPressureCallback.run();
        }
    }
    /**
     * Boolean used to store the latest back pressure state
     * Does this need to be volatile?
     */
    private volatile boolean m_hadBackPressure = false;

    /**
     * The maximum amount that the stream will attempt to write to the channel under any circumstances
     */
    static final int MAX_GATHERING_WRITE = 262144;

    /**
     * Does the work of queueing addititional buffers that have been serialized
     * and choosing between gathering and regular writes to the channel. Also splits up very large
     * writes of HeapByteBuffers into many smaller writes so Java doesn't allocate a monster DirectByteBuffer
     * that will never be freed
     * @param channel
     * @param additional
     * @return
     * @throws IOException
     */
    int drainTo (final GatheringByteChannel channel, final BBContainer additional[]) throws IOException {

        /*
         * Add the containers that were serialized when swapAndSerializeQueuedWrites was
         * invoked to the end of the queue so they will eventually be written
         */
        if (additional != null) {
            for (final BBContainer c : additional) {
                m_queuedBuffers.offer(c);
            }
        }

        int bytesWritten = 0;
        long rc = 0;
        do {
            //For gathering write (many small writes) store the list of buffers here
            ByteBuffer buffers[] = null;
            //For regular writes store the buffer to write here
            ByteBuffer buffer = null;

            /*
             * Nothing to write
             */
            if (m_queuedBuffers.isEmpty()) {
                if (m_hadBackPressure) {
                    backpressureEnded();
                }
                m_lastPendingWriteTime = -1;
                updateQueued(-bytesWritten);
                m_bytesWritten += bytesWritten;
                return bytesWritten;
            }

            /*
             * Peek the first buffer and inspect it to see if it needs any special handling
             * If it is to large to use as part of a gathering write then branch and just focus on
             * writing the one
             */
            final int queuedForWrite = 0;
            final BBContainer peekedBuffer = m_queuedBuffers.peek();
            if (peekedBuffer.b.remaining() > MAX_GATHERING_WRITE) {
                /*
                 * If the buffer is not direct and it is this large it should be split up in separate writes
                 */
                if (!peekedBuffer.b.isDirect()) {
                    /**
                     * Split a big heap byte buffer into many smaller slices
                     * so Java doesn't allocate a bunch of direct ByteBuffers
                     */
                    //The source buffer for the slices that holds the memory goes in first
                    //so it will be discarded once all the slices have been read.
                    m_queuedBuffers.push(peekedBuffer);
                    final int originalPosition = peekedBuffer.b.position();
                    do {
                        final int amountToSplit = Math.min(peekedBuffer.b.remaining(), MAX_GATHERING_WRITE);
                        peekedBuffer.b.position(peekedBuffer.b.limit() - amountToSplit);
                        final BBContainer splice = DBBPool.wrapBB(peekedBuffer.b.slice());
                        m_queuedBuffers.push(splice);
                        peekedBuffer.b.limit(peekedBuffer.b.position());
                        peekedBuffer.b.position(originalPosition);
                    }
                    while(peekedBuffer.b.hasRemaining());
                    buffer = m_queuedBuffers.peek().b;
                } else {
                    //Elect to do a single regular write instead of all the gathering write work
                    //It is okay not to split it into many smaller buffers because it is direct
                    buffer = peekedBuffer.b;
                }
            } else {
                /*
                 * Iterate over the queued buffers until we have 10, there are none left, or
                 * adding another would make the write too big
                 */
                final int queuedBuffersSize = m_queuedBuffers.size();
                buffers = new ByteBuffer[queuedBuffersSize < 10 ? queuedBuffersSize : 10];
                int ii = 0;
                for (final BBContainer c : m_queuedBuffers) {

                    /*
                     * Don't queue insanely large gathering writes. It does bad things
                     * This catches a series of writes that would be too large.
                     * This doesn't skip the buffer that is too large and look
                     * for a smaller one in order to ensure that a large buffer
                     * doesn't get neglected
                     */
                    final int potentialQueuedForWrite = queuedForWrite + c.b.remaining();
                    if (potentialQueuedForWrite > MAX_GATHERING_WRITE) {
                        //Create a new correctly sized array
                        //to pass in for the gathering write
                        final ByteBuffer oldArray[] = buffers;
                        buffers = new ByteBuffer[ii];
                        for(int zz = 0; zz < ii; zz++) {
                            buffers[zz] = oldArray[zz];
                        }
                        break;
                    }

                    /*
                     * Never ever ever try and do a gathering write with a buffer that is
                     * not direct. Java will allocate a DirectByteBuffer every time and not
                     * pool them at all. If the buffer is not direct it will be skipped and
                     * the gathering write will go ahead with what has already come off of the queue
                     * @TODO A potential optimization might be to continue looking ahead in the queue for
                     * more direct buffers. Probably more complex then it is worth.
                     */
                    if (!c.b.isDirect()) {
                        if (ii == 0) {
                            buffers = null;
                            buffer = c.b;
                            break;
                        } else {
                            final ByteBuffer oldArray[] = buffers;
                            buffers = new ByteBuffer[ii];
                            for(int zz = 0; zz < ii; zz++) {
                                buffers[zz] = oldArray[zz];
                            }
                            break;
                        }
                    }

                    /*
                     * The regular case where there is nothing wrong
                     * and the buffer can be added to the list for the gathering write
                     */
                    buffers[ii++] = c.b;
                    if (ii == 10) {
                        break;
                    }
                }
            }

            /*
             * Choose between a gathering write vs. a single buffer write based
             * on the presence of the buffers array
             */
            rc = 0;
            if (buffers != null) {
                assert(checkAllDirect(buffers));
                rc = channel.write (buffers);

                //Discard the buffer back to a pool if no data remains
                for (final ByteBuffer b : buffers) {
                    if (!b.hasRemaining()) {
                        m_queuedBuffers.poll().discard();
                    } else {
                        if (!m_hadBackPressure) {
                            backpressureStarted();
                        }
                        break;
                    }
                }
            } else {
                rc = channel.write(buffer);

                //Discard the buffer back to a pool if no data remains
                if (buffer.hasRemaining()) {
                    if (!m_hadBackPressure) {
                        backpressureStarted();
                    }
                } else {
                    m_queuedBuffers.poll().discard();
                }
            }
            bytesWritten += rc;

        } while (rc > 0);

        //This extra check is necessary because sometimes a buffer with nothing remaining
        //has to be queued in the above loop resulting in rc == 0. Since rc == 0
        //it won't loop around a last time and see that there are no more queued buffers
        //and thus no backpressure
        if (m_queuedBuffers.isEmpty() && m_hadBackPressure) {
            backpressureEnded();
        }

        if (!isEmpty()) {
            if (bytesWritten > 0) {
                m_lastPendingWriteTime = EstTime.currentTimeMillis();
            }
        } else {
            m_lastPendingWriteTime = -1;
        }
        updateQueued(-bytesWritten);
        m_bytesWritten += bytesWritten;
        return bytesWritten;
    }

    /**
     * Used for assertions. Returns false if one of the buffers is not direct
     * @param buffers
     * @return
     */
    private final boolean checkAllDirect(final ByteBuffer buffers[]) {
        for (final ByteBuffer b : buffers ) {
           if (!b.isDirect()) {
               return false;
           }
        }
        return true;
    }

    /**
     * Queued a container for writing. This isn't the ideal API to use since the serialization has been done
     * outside of a network thread
     * @param c
     */
    @Override
    public boolean enqueue(final BBContainer c) {
        assert(c != null);
        assert(c.b != null);
        if (c.b.remaining() == 0) {
            c.discard();
            return false;
        }
        synchronized (this) {
            if (m_isShutdown) {
                c.discard();
                return false;
            }
            updateLastPendingWriteTime();
            updateQueued(c.b.remaining());
            m_queuedBuffers.offer(c);
            m_port.setInterests( SelectionKey.OP_WRITE, 0);
        }
        return true;
    }

    /**
     * Queue a FastSerializable object for writing. This is 3rd best way to serialize and queue messages.
     * Since no expected message size is provided the default one for this port is used which may not be accurate
     * for this particular message. Because FastSerializer is used to serialize the object there is some
     * overhead incurred if the FastSerializer has to resize and every time the FastSerializer has to check
     * if it needs to grow.
     * @param f
     */
    @Override
    public boolean enqueue(final FastSerializable f) {
        synchronized (this) {
            if (m_isShutdown) {
                return false;
            }
            updateLastPendingWriteTime();
            m_queuedWrites.offer(new DeferredSerialization() {
                @Override
                public BBContainer serialize(final DBBPool pool) throws IOException {
                    final FastSerializer fs = new FastSerializer(pool, m_port.m_expectedOutgoingMessageSize);
                    return fs.writeObjectForMessaging(f);
                }

                @Override
                public void cancel() {}
            });
            m_port.setInterests( SelectionKey.OP_WRITE, 0);
        }
        return true;
    }

    /**
     * Queue a FastSerializable object for writing. This is 2nd best way to serialize and queue messages.
     * The expected message size is used to size the initial allocation for the FastSerializer.
     * Because FastSerializer is used to serialize the object there is some over head incurred
     * when the FastSerializer has to check if it needs to grow, but the cost is pretty minor compared
     * to the cost of actually growing the FastSerializer.
     * @param f
     */
    @Override
    public boolean enqueue(final FastSerializable f, final int expectedSize) {
        synchronized (this) {
            if (m_isShutdown) {
                return false;
            }
            updateLastPendingWriteTime();
            m_queuedWrites.offer(new DeferredSerialization() {
                @Override
                public BBContainer serialize(final DBBPool pool) throws IOException {
                    final FastSerializer fs = new FastSerializer(pool, expectedSize);
                    return fs.writeObjectForMessaging(f);
                }

                @Override
                public void cancel() {}
            });
            m_port.setInterests( SelectionKey.OP_WRITE, 0);
        }
        return true;
    }

    /**
     * Queue a message and defer the serialization of the message until later. This is the ideal mechanism
     * for serializing and queueing network writes. It allows the sender to define an efficient serialization
     * mechanism that performs a single allocation of the correct size without the overhead of FastSerializer
     * which has to constantly check if it needs to grow.
     * @param ds A deferred serialization task that will generate the message
     */
    @Override
    public boolean enqueue(final DeferredSerialization ds) {
        synchronized (this) {
            if (m_isShutdown) {
                ds.cancel();
                return false;
            }
            updateLastPendingWriteTime();
            m_queuedWrites.offer(ds);
            m_port.setInterests( SelectionKey.OP_WRITE, 0);
        }
        return true;
    }

    /**
     * Queue a ByteBuffer for writing to the network. If the ByteBuffer is not direct then it will
     * be copied to a DirectByteBuffer if it is less then DBBPool.MAX_ALLOCATION_SIZE. This method
     * is a backup for code that isn't able to defer its serialization to a network thread
     * for whatever reason. It is reasonably efficient if a DirectByteBuffer is passed in,
     * but it would be better to keep allocations of DirectByteBuffers inside the network pools.
     * @param b
     */
    @Override
    public boolean enqueue(final ByteBuffer b) {
        assert(b != null);
        assert(!b.isDirect());//Dont' queue direct buffers, they leak memory without a container
        if (b.remaining() == 0) {
            return false;
        }

        synchronized (this) {
            if (m_isShutdown) {
                return false;
            }

            updateLastPendingWriteTime();

            /*
             * Attempt to use one of our own pooled direct byte buffers
             * so that a gathering write can be done later. Java gathering
             * writes spam direct byte buffers if many regular BBs are used
             */
            if (b.remaining() < DBBPool.MAX_ALLOCATION_SIZE){
                m_queuedWrites.offer(new DeferredSerialization() {
                    @Override
                    public BBContainer serialize(final DBBPool pool) {
                        final BBContainer c = pool.acquire(b.remaining());
                        assert(c.b.isDirect());
                        c.b.put(b);
                        c.b.flip();
                        return c;
                    }

                    @Override
                    public void cancel() {}
                });
            } else {
                updateQueued(b.remaining());
                m_queuedBuffers.offer(DBBPool.wrapBB(b));
            }
            m_port.setInterests( SelectionKey.OP_WRITE, 0);
        }
        return true;
    }

    /**
     * Swap the two queues of DeferredSerializations and serialize everything in the queue
     * and return the resulting ByteBuffers as an array.
     * @return
     * @throws IOException
     */
    final BBContainer[] swapAndSerializeQueuedWrites(final DBBPool pool) throws IOException {
        ArrayDeque<DeferredSerialization> oldlist;
        synchronized (this) {
            if (m_queuedWrites.isEmpty()) {
                return null;
            } else {
                if (m_queuedWrites == m_queuedWrites1) {
                    oldlist = m_queuedWrites1;
                    m_queuedWrites = m_queuedWrites2;
                }
                else {
                    oldlist = m_queuedWrites2;
                    m_queuedWrites = m_queuedWrites1;
                }
            }
        }
        final BBContainer results[] = new BBContainer[oldlist.size()];
        int ii = 0;
        DeferredSerialization ds = null;
        int bytesQueued = 0;
        while ((ds = oldlist.poll()) != null) {
            results[ii] = ds.serialize(pool);
            bytesQueued += results[ii].b.remaining();
            assert(results[ii] != null);
            assert(results[ii].b != null);
            ii++;
        }
        updateQueued(bytesQueued);
        return results;
    }

    /**
     * Free the pool resources that are held by this WriteStream. The pool itself is thread local
     * and will be freed when the thread terminates.
     */
    synchronized void shutdown() {
        int bytesReleased = 0;
        m_isShutdown = true;
        BBContainer c = null;
        while ((c = m_queuedBuffers.poll()) != null) {
            bytesReleased += c.b.remaining();
            c.discard();
        }
        updateQueued(-bytesReleased);
        DeferredSerialization ds = null;
        while ((ds = m_queuedWrites.poll()) != null) {
            ds.cancel();
        }
    }

    @Override
    public synchronized int calculatePendingWriteDelta(final long now) {
        if (m_lastPendingWriteTime == -1) {
            return 0;
        }
        return (int)(now - m_lastPendingWriteTime);
    }

    private void updateLastPendingWriteTime() {
        if (m_lastPendingWriteTime == -1) {
            m_lastPendingWriteTime = EstTime.currentTimeMillis();
        }
    }

    private void updateQueued(int queued) {
        if (m_monitor != null) {
            m_monitor.queue(queued);
        }
    }
}
