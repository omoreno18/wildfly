/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.ejb3.timerservice;

import java.io.Serializable;
import java.util.Date;
import java.util.concurrent.Semaphore;
import javax.ejb.EJBException;
import javax.ejb.ScheduleExpression;
import javax.ejb.Timer;
import javax.ejb.TimerHandle;

import org.jboss.as.ejb3.component.allowedmethods.AllowedMethodsInformation;
import org.jboss.as.ejb3.component.allowedmethods.MethodType;
import org.jboss.as.ejb3.logging.EjbLogger;
import org.jboss.as.ejb3.timerservice.spi.TimedObjectInvoker;

/**
 * Implementation of Enterprise Beans 3.1 {@link Timer}
 *
 * @author <a href="mailto:cdewolf@redhat.com">Carlo de Wolf</a>
 * @version $Revision: $
 */
public class TimerImpl implements Timer {
    /**
     * The output format with the cached final values, after the first toString() invocation
     */
    private String toStringTemplate;
    /**
     * Unique id for this timer instance
     */
    protected final String id;

    /**
     * The timer state
     */
    protected volatile TimerState timerState;

    /**
     * The {@link javax.ejb.TimerService} through which this timer was created
     */
    protected final TimerServiceImpl timerService;

    /**
     * The {@link TimedObjectInvoker} to which this timer corresponds
     */
    protected final TimedObjectInvoker timedObjectInvoker;

    /**
     * The info which was passed while creating the timer.
     */
    protected Serializable info;

    /**
     * Indicates whether the timer is persistent
     */
    protected final boolean persistent;

    /**
     * The initial (first) expiry date of this timer
     */
    protected final Date initialExpiration;

    /**
     * The duration in milli sec. between timeouts
     */
    protected final long intervalDuration;

    /**
     * Next expiry date of this timer
     */
    protected volatile Date nextExpiration;

    /**
     * The date of the previous run of this timer
     */
    protected volatile Date previousRun;

    private final String timedObjectId;

    /**
     * In use lock. This is held by timer invocations and cancellation within the scope of a transaction, all changes
     * to timer state after creation should be done within this lock, to prevent state being overwritten by multiple
     * threads.
     * <p/>
     * If the timer cancelled, but then rolled back we do not want the timer task to see this cancellation.
     *
     * todo: we can probably just use a sync block here
     */
    private final Semaphore inUseLock = new Semaphore(1);

    /**
     * The executing thread which is processing the timeout task This is only set to executing thread for TimerState.IN_TIMEOUT
     * and TimerState.RETRY_TIMEOUT
     */
    private volatile Thread executingThread;

    /**
     * Creates a {@link TimerImpl}
     *
     * @param builder          The builder with the timer information
     * @param service          The timer service through which this timer was created
     */
    protected TimerImpl(Builder builder, TimerServiceImpl service) {
        assert builder.id != null : "id is null";

        this.id = builder.id;
        this.timedObjectId = builder.timedObjectId;
        this.info = builder.info;
        this.persistent = builder.persistent;
        this.initialExpiration = builder.initialDate;
        this.intervalDuration = builder.repeatInterval;
        if (builder.newTimer && builder.nextDate == null) {
            this.nextExpiration = initialExpiration;
        } else {
            this.nextExpiration = builder.nextDate;
        }
        this.previousRun = builder.previousRun;
        this.timerState = builder.timerState;
        this.timerService = service;
        this.timedObjectInvoker = service.getInvoker();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void cancel() throws IllegalStateException, EJBException {
        try {
            timerService.cancelTimer(this);
        } catch (InterruptedException e) {
            throw new EJBException(e);
        }
    }

    /**
     * Returns the id of this timer
     *
     * @return
     */
    public String getId() {
        return this.id;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isCalendarTimer() throws IllegalStateException, EJBException {
        // first check whether this timer has expired or cancelled
        this.assertTimerState();

        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TimerHandle getHandle() throws IllegalStateException, EJBException {
        // make sure it's in correct state
        this.assertTimerState();

        // for non-persistent timers throws an exception (mandated by Enterprise Beans 3 spec)
        if (!persistent) {
            throw EjbLogger.EJB3_TIMER_LOGGER.invalidTimerHandlersForPersistentTimers("Enterprise Beans 3.1 Spec 18.2.6");
        }
        return new TimerHandleImpl(id, timedObjectId, timerService);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isPersistent() throws IllegalStateException, EJBException {
        // make sure the call is allowed in the current timer state
        this.assertTimerState();
        return this.persistent;
    }

    /**
     * {@inheritDoc}
     *
     * @see #getTimerInfo()
     */
    @Override
    public Serializable getInfo() throws IllegalStateException, EJBException {
        // make sure this call is allowed
        this.assertTimerState();
        return getTimerInfo();
    }

    /**
     * This method is similar to {@link #getInfo()}, except that this method does <i>not</i> check the timer state
     * and hence does <i>not</i> throw either {@link IllegalStateException} or {@link javax.ejb.NoSuchObjectLocalException}
     * or {@link javax.ejb.EJBException}.
     *
     * @return the timer info; if not available in-memory, retrieve it from persistence
     */
    public Serializable getTimerInfo() {
        if (info != Object.class) {
            return info;
        }
        return timerService.getPersistedTimerInfo(this);
    }

    /**
     * Obtains the timer info cached in memory, without checking the persistent store.
     *
     * @return the cached timer info
     */
    public Serializable getCachedTimerInfo() {
        return info;
    }

    /**
     * Sets the timer info to a new value.
     * The purpose of this method is for {@code DatabaseTimerPersistence} to reset
     * the cached timer info. It should not be used for other purposes.
     *
     * @param newInfo the new timer info, typically null or an empty holder value
     */
    public void setCachedTimerInfo(final Serializable newInfo) {
        info = newInfo;
    }

    /**
     * {@inheritDoc}
     *
     * @see #getNextExpiration()
     */
    @Override
    public Date getNextTimeout() throws IllegalStateException, EJBException {
        // first check the validity of the timer state
        this.assertTimerState();
        if (this.nextExpiration == null) {
            throw EjbLogger.EJB3_TIMER_LOGGER.noMoreTimeoutForTimer(this);
        }
        return this.nextExpiration;
    }

    /**
     * This method is similar to {@link #getNextTimeout()}, except that this method does <i>not</i> check the timer state
     * and hence does <i>not</i> throw either {@link IllegalStateException} or {@link javax.ejb.NoSuchObjectLocalException}
     * or {@link javax.ejb.EJBException}.
     *
     * @return
     */
    public Date getNextExpiration() {
        return this.nextExpiration;
    }

    /**
     * Sets the next timeout of this timer
     *
     * @param next The next scheduled timeout of this timer
     */
    public void setNextTimeout(Date next) {
        if(next == null) {
            setTimerState(TimerState.EXPIRED, null);
        }
        this.nextExpiration = next;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ScheduleExpression getSchedule() throws IllegalStateException, EJBException {
        this.assertTimerState();
        throw EjbLogger.EJB3_TIMER_LOGGER.invalidTimerNotCalendarBaseTimer(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getTimeRemaining() throws IllegalStateException, EJBException {
        // TODO: Rethink this implementation

        // first check the validity of the timer state
        this.assertTimerState();
        if (this.nextExpiration == null) {
            throw EjbLogger.EJB3_TIMER_LOGGER.noMoreTimeoutForTimer(this);
        }
        long currentTimeInMillis = System.currentTimeMillis();
        long nextTimeoutInMillis = this.nextExpiration.getTime();

        // if the next expiration is *not* in future and the repeat interval isn't
        // a positive number (i.e. no repeats) then there won't be any more timeouts.
        // So throw a NoMoreTimeoutsException.
        // NOTE: We check for intervalDuration and not just nextExpiration because,
        // it's a valid case where the nextExpiration is in past (maybe the server was
        // down when the timeout was expected)
        //      if (nextTimeoutInMillis < currentTimeInMillis && this.intervalDuration <= 0)
        //      {
        //         throw new NoMoreTimeoutsException("No more timeouts for timer " + this);
        //      }
        return nextTimeoutInMillis - currentTimeInMillis;
    }

    public boolean isAutoTimer() {
        return false;
    }

    /**
     * Returns the initial (first) timeout date of this timer
     *
     * @return
     */
    public Date getInitialExpiration() {
        return this.initialExpiration;
    }

    /**
     * Returns the interval (in milliseconds), between timeouts, of this timer.
     *
     * @return
     */
    public long getInterval() {
        return this.intervalDuration;
    }

    /**
     * Returns the timed object id to which this timer belongs
     *
     * @return
     */
    public String getTimedObjectId() {
        return timedObjectId;
    }

    /**
     * Returns the timer service through which this timer was created
     *
     * @return
     */
    public TimerServiceImpl getTimerService() {
        return this.timerService;
    }

    /**
     * Returns true if this timer is active. Else returns false.
     * <p>
     * A timer is considered to be "active", if its {@link TimerState}
     * is neither of the following:
     * <ul>
     * <li>{@link TimerState#CANCELED}</li>
     * <li>{@link TimerState#EXPIRED}</li>
     * <li>has not been suspended</li>
     * </ul>
     * <p/>
     * And if the corresponding timer service is still up
     * <p/>
     * </p>
     *
     * @return
     */
    public boolean isActive() {
        return timerService.isStarted() && !isCanceled() && !isExpired() && (timerState == TimerState.CREATED || timerService.isScheduled(getId()));
    }

    /**
     * Returns true if this timer is in {@link TimerState#CANCELED} state. Else returns false.
     *
     * @return
     */
    public boolean isCanceled() {
        return timerState == TimerState.CANCELED;
    }

    /**
     * Returns true if this timer is in {@link TimerState#EXPIRED} state. Else returns false
     *
     * @return
     */
    public boolean isExpired() {
        return timerState == TimerState.EXPIRED;
    }

    /**
     * Returns true if this timer is in {@link TimerState#RETRY_TIMEOUT}. Else returns false.
     *
     * @return
     */
    public boolean isInRetry() {
        return timerState == TimerState.RETRY_TIMEOUT;
    }

    /**
     * Returns the {@link java.util.Date} of the previous timeout of this timer
     *
     * @return
     */
    public Date getPreviousRun() {
        return this.previousRun;
    }

    /**
     * Sets the {@link java.util.Date} of the previous timeout of this timer
     *
     * @param previousRun
     */
    public void setPreviousRun(Date previousRun) {
        this.previousRun = previousRun;
    }

    /**
     * Returns the current state of this timer
     *
     * @return
     */
    public TimerState getState() {
        return this.timerState;
    }

    /**
     * Returns the executing thread which is processing the timeout task
     *
     * @return the executingThread
     */
    protected Thread getExecutingThread() {
        return executingThread;
    }

    /**
     * Asserts that the timer is <i>not</i> in any of the following states:
     * <ul>
     * <li>{@link TimerState#CANCELED}</li>
     * <li>{@link TimerState#EXPIRED}</li>
     * </ul>
     *
     * @throws javax.ejb.NoSuchObjectLocalException if the txtimer was canceled or has expired
     */
    protected void assertTimerState() {
        if (timerState == TimerState.EXPIRED)
            throw EjbLogger.EJB3_TIMER_LOGGER.timerHasExpired(id);
        if (timerState == TimerState.CANCELED)
            throw EjbLogger.EJB3_TIMER_LOGGER.timerWasCanceled(id);
        AllowedMethodsInformation.checkAllowed(MethodType.TIMER_SERVICE_METHOD);
    }

    /**
     * Sets the state and timer task executing thread of this timer
     *
     * @param state The state of this timer
     * @param thread The executing thread which is processing the timeout task
     */
    public void setTimerState(TimerState state, Thread thread) {
        assert ((state == TimerState.IN_TIMEOUT || state == TimerState.RETRY_TIMEOUT) && thread != null) || thread == null : "Invalid to set timer state " + state + " with executing Thread " + thread;
        this.timerState = state;
        this.executingThread = thread;
    }

    /**
     * Suspends any currently scheduled task for this timer
     * <p>
     * Note that, suspend does <b>not</b> cancel the {@link Timer}. Instead,
     * it just cancels the <b>next scheduled timeout</b>. So once the {@link Timer}
     * is restored (whenever that happens), the {@link Timer} will continue to
     * timeout at appropriate times.
     * </p>
     */
    // TODO: Revisit this method, we probably don't need this any more.
    // In terms of implementation, this is just equivalent to cancelTimeout() method
    public void suspend() {
        // cancel any scheduled timer task (Future) for this timer
        // delegate to the timerservice, so that it can cancel any scheduled Future
        // for this timer
        this.timerService.cancelTimeout(this);
    }

    /**
     * Triggers timer, outside of normal expiration. Only used when running an explicit management trigger operation.
     *
     * The tigger operation simply runs the callback, it does not modify the timer state in any way, and there is no
     * protection against overlapping events when running it. This is the expected behaviour, as otherwise the semantics
     * of dealing with concurrent execution is complex and kinda weird.
     */
    public void invokeOneOff() throws Exception {
        this.getTimerTask().invokeBeanMethod(this);
    }
    /**
     * Creates and schedules a {@link TimerTask} for the next timeout of this timer
     *
     * @param newTimer <code>true</code> if this is a new timer being scheduled, and not a re-schedule due to a timeout
     */
    public void scheduleTimeout(boolean newTimer) {
        // just delegate to timerservice, for it to do the actual scheduling
        this.timerService.scheduleTimeout(this, newTimer);
    }

    /**
     * Returns the task which handles the timeouts of this {@link TimerImpl}
     *
     * @return
     * @see TimerTask
     */
    protected TimerTask getTimerTask() {
        return new TimerTask(this);
    }

    public void lock() throws InterruptedException {
        inUseLock.acquire();
    }

    public void unlock() {
        inUseLock.release();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || this.getClass() != o.getClass()) return false;

        final TimerImpl otherTimer = (TimerImpl) o;

        if (!id.equals(otherTimer.id)) return false;
        return timedObjectId.equals(otherTimer.timedObjectId);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /**
     * A nice formatted string output for this timer
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        if (this.toStringTemplate == null) {
            // initialize with the first invocation
            StringBuilder sb = new StringBuilder();
            sb.append("[id=");
            sb.append(this.id);
            sb.append(" timedObjectId=");
            sb.append(timedObjectId);
            sb.append(" auto-timer?:");
            sb.append(this.isAutoTimer());
            sb.append(" persistent?:");
            sb.append(this.persistent);
            sb.append(" timerService=");
            sb.append(this.timerService);
            sb.append(" previousRun=");
            sb.append(this.previousRun);
            sb.append(" initialExpiration=");
            sb.append(this.initialExpiration);
            sb.append(" intervalDuration(in milli sec)=");
            sb.append(this.intervalDuration);
            this.toStringTemplate = sb.toString();
        }
        // complete with the dynamic values
        StringBuilder sb = new StringBuilder(this.toStringTemplate);
        sb.append(" nextExpiration=");
        sb.append(this.nextExpiration);
        sb.append(" timerState=");
        sb.append(this.timerState);
        sb.append(" info=");
        sb.append(this.info);
        sb.append("]");
        return sb.toString();
   }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        protected String id;
        protected String timedObjectId;
        protected Date initialDate;
        protected long repeatInterval;
        protected Date nextDate;
        protected Date previousRun;
        protected Serializable info;
        protected TimerState timerState;
        protected boolean persistent;
        protected boolean newTimer;

        public Builder setId(final String id) {
            this.id = id;
            return this;
        }

        public Builder setTimedObjectId(final String timedObjectId) {
            this.timedObjectId = timedObjectId;
            return this;
        }

        public Builder setInitialDate(final Date initialDate) {
            this.initialDate = initialDate;
            return this;
        }

        public Builder setRepeatInterval(final long repeatInterval) {
            this.repeatInterval = repeatInterval;
            return this;
        }

        public Builder setNextDate(final Date nextDate) {
            this.nextDate = nextDate;
            return this;
        }

        public Builder setPreviousRun(final Date previousRun) {
            this.previousRun = previousRun;
            return this;
        }

        public Builder setInfo(final Serializable info) {
            this.info = info;
            return this;
        }

        public Builder setTimerState(final TimerState timerState) {
            this.timerState = timerState;
            return this;
        }

        public Builder setPersistent(final boolean persistent) {
            this.persistent = persistent;
            return this;
        }

        public Builder setNewTimer(final boolean newTimer) {
            this.newTimer = newTimer;
            return this;
        }

        public String getId() {
            return id;
        }

        public String getTimedObjectId() {
            return timedObjectId;
        }

        public TimerImpl build(final TimerServiceImpl timerService) {
            return new TimerImpl(this, timerService);
        }
    }
}
