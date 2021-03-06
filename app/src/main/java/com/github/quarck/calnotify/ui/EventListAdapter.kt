//
//   Calendar Notifications Plus  
//   Copyright (C) 2016 Sergey Parshin (s.parshin.sc@gmail.com)
//
//   This program is free software; you can redistribute it and/or modify
//   it under the terms of the GNU General Public License as published by
//   the Free Software Foundation; either version 3 of the License, or
//   (at your option) any later version.
// 
//   This program is distributed in the hope that it will be useful,
//   but WITHOUT ANY WARRANTY; without even the implied warranty of
//   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//   GNU General Public License for more details.
//
//   You should have received a copy of the GNU General Public License
//   along with this program; if not, write to the Free Software Foundation,
//   Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301  USA
//

package com.github.quarck.calnotify.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.helper.ItemTouchHelper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RelativeLayout
import android.widget.TextView
import com.github.quarck.calnotify.R
import com.github.quarck.calnotify.Settings
import com.github.quarck.calnotify.calendar.EventAlertRecord
import com.github.quarck.calnotify.textutils.EventFormatter
import com.github.quarck.calnotify.utils.adjustCalendarColor
import com.github.quarck.calnotify.utils.find

interface EventListCallback {
    fun onItemClick(v: View, position: Int, eventId: Long): Unit
    fun onItemDismiss(v: View, position: Int, eventId: Long): Unit
    fun onItemSnooze(v: View, position: Int, eventId: Long): Unit
    fun onItemRemoved(event: EventAlertRecord)
    fun onItemRestored(event: EventAlertRecord) // e.g. undo
    fun onScrollPositionChange(newPos: Int)
}

@Suppress("DEPRECATION")
class EventListAdapter(
    val context: Context,
    val useCompactView: Boolean,
    val cardVewResourceId: Int,
    val callback: EventListCallback)

: RecyclerView.Adapter<EventListAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View)
    : RecyclerView.ViewHolder(itemView) {
        var eventId: Long = 0;

        var eventHolder: RelativeLayout?
        var eventTitleText: TextView
        var eventTitleLayout: RelativeLayout?
        var eventDateText: TextView
        var eventTimeText: TextView
        var eventLocatoinText: TextView?
        var snoozedUntilText: TextView?
        val compactViewCalendarColor: View?

        val compactViewContentLayout: RelativeLayout?
        var undoLayout: RelativeLayout?

        var snoozeButton: Button?
        var dismissButton: Button?
        var undoButton: Button?

        var calendarColor: ColorDrawable

        init {
            eventHolder = itemView.find<RelativeLayout>(R.id.card_view_main_holder)
            eventTitleText = itemView.find<TextView>(R.id.card_view_event_name)
            eventTitleLayout = itemView.find<RelativeLayout?>(R.id.card_view_event_title_layout)

            eventDateText = itemView.find<TextView>(R.id.card_view_event_date)
            eventTimeText = itemView.find<TextView>(R.id.card_view_event_time)
            snoozedUntilText = itemView.find<TextView>(R.id.card_view_snoozed_until)

            eventLocatoinText = itemView.find<TextView?>(R.id.card_view_location)

            snoozeButton = itemView.find<Button?>(R.id.card_view_button_reschedule)
            dismissButton = itemView.find<Button?>(R.id.card_view_button_dismiss)

            undoLayout = itemView.find<RelativeLayout?>(R.id.event_card_undo_layout)

            compactViewContentLayout = itemView.find<RelativeLayout?>(R.id.compact_view_content_layout)
            compactViewCalendarColor = itemView.find<View?>(R.id.compact_view_calendar_color)

            undoButton = itemView.find<Button?>(R.id.card_view_button_undo)

            calendarColor = ColorDrawable(0)

            val itemClickListener = View.OnClickListener {
                callback.onItemClick(itemView, adapterPosition, eventId);
            }

            eventHolder?.setOnClickListener(itemClickListener)
            eventLocatoinText?.setOnClickListener(itemClickListener)
            eventDateText.setOnClickListener(itemClickListener)
            eventTimeText.setOnClickListener(itemClickListener)

            dismissButton?.setOnClickListener {
                callback.onItemDismiss(itemView, adapterPosition, eventId);
            }

            snoozeButton?.setOnClickListener {
                callback.onItemSnooze(itemView, adapterPosition, eventId);
            }
        }
    }

    private var events = arrayOf<EventAlertRecord>();

    private var _recyclerView: RecyclerView? = null
    var recyclerView: RecyclerView?
        get() = _recyclerView
        set(value) {
            _recyclerView = value
            onRecycleViewRegistered(_recyclerView)
        }

    private val primaryColor: Int
    private val changeString: String
    private val snoozeString: String

    private var currentScrollPosition: Int = 0

    private val pendingEventRemoveRunnables = mutableMapOf<EventAlertRecord, Runnable>()
    private var eventsPendingRemoval = mutableListOf<EventAlertRecord>()

    private val eventFormatter = EventFormatter(context)

    val scrollPosition: Int
        get() = currentScrollPosition

    init {
        primaryColor = context.resources.getColor(R.color.primary)
        changeString = context.resources.getString(R.string.card_view_btn_change);
        snoozeString = context.resources.getString(R.string.card_view_btn_snooze);
    }

    private fun onRecycleViewRegistered(_recyclerView: RecyclerView?) {

        _recyclerView?.addOnScrollListener (
            object: RecyclerView.OnScrollListener() {
                override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                    currentScrollPosition += dy;
                    callback.onScrollPositionChange(currentScrollPosition)
                }
            })

        if (useCompactView)
            setUpItemTouchHelper(_recyclerView, context)
    }

    private fun setUpItemTouchHelper(_recyclerView: RecyclerView?, context: Context) {

        val itemTouchCallback =
            object: ItemTouchHelper.Callback() {

                internal val lightweightSwipe = Settings(context).lightweightSwipe
                internal val escapeVelocityMultiplier = if (lightweightSwipe) 2.0f else 5.0f

                internal val background = ColorDrawable(context.resources.getColor(R.color.material_red))
                internal var xMark = context.resources.getDrawable(R.drawable.ic_clear_white_24dp)
                internal var xMarkMargin = context.resources.getDimension(R.dimen.ic_clear_margin).toInt()

                init {
                    xMark.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP)
                }

                override fun getMovementFlags(recyclerView: RecyclerView?, viewHolder: RecyclerView.ViewHolder?): Int {
                    val position = viewHolder!!.adapterPosition
                    val adapter = recyclerView?.adapter as EventListAdapter?

                    if (adapter == null)
                        return 0

                    if (adapter.isPendingRemoval(position))
                        return 0

                    return  makeFlag(ItemTouchHelper.ACTION_STATE_IDLE, ItemTouchHelper.RIGHT or ItemTouchHelper.LEFT) or
                        makeFlag(ItemTouchHelper.ACTION_STATE_SWIPE, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
                }

                override fun onMove(recyclerView: RecyclerView?, viewHolder: RecyclerView.ViewHolder?, target: RecyclerView.ViewHolder?): Boolean {
                    return false
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder?, direction: Int) {
                    val swipedPosition = viewHolder?.adapterPosition
                    if (swipedPosition != null) {
                        _recyclerView?.itemAnimator?.changeDuration = 0;

                        val event = getEventAtPosition(swipedPosition)

                        if (event != null) {
                            removeWithUndo(event)
                            callback.onItemRemoved(event)
                        }
                    }
                }

                override fun isLongPressDragEnabled() = false

                override fun isItemViewSwipeEnabled() = true

                /* From documentation:
                 * Defines the minimum velocity which will be considered as a swipe action by the user.
                 * You can increase this value to make it harder to swipe or decrease it to make
                 * it easier. */
                override fun getSwipeEscapeVelocity(defaultValue: Float) = defaultValue * escapeVelocityMultiplier

                /* From documentation:
                 * Defines the maximum velocity ItemTouchHelper will ever calculate for pointer
                 * movements.
                 * If you increase the value, it will be easier for the user to swipe diagonally and
                 * if you decrease the value, user will need to make a rather straight finger movement
                 * to trigger a swipe.*/
                override fun getSwipeVelocityThreshold(defaultValue: Float) = defaultValue / 3.0f

                /* From documentation:
                 * Default value is .5f, which means, to swipe a View, user must move the View at
                 * least half of RecyclerView's width or height, depending on the swipe direction. */
//                override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = 0.5f

                override fun onChildDraw(
                    c: Canvas, recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    dX: Float, dY: Float,
                    actionState: Int, isCurrentlyActive: Boolean) {

                    val itemView = viewHolder.itemView

                    if (viewHolder.adapterPosition == -1)
                        return

                    if (dX < 0)
                        background.setBounds(itemView.right + dX.toInt(), itemView.top, itemView.right, itemView.bottom)
                    else
                        background.setBounds(itemView.left, itemView.top, itemView.left + dX.toInt(), itemView.bottom)

                    background.draw(c)

                    val itemHeight = itemView.bottom - itemView.top
                    val intrinsicWidth = xMark.intrinsicWidth
                    val intrinsicHeight = xMark.intrinsicWidth


                    if (dX < 0) {
                        val xMarkLeft = itemView.right - xMarkMargin - intrinsicWidth
                        val xMarkRight = itemView.right - xMarkMargin
                        val xMarkTop = itemView.top + (itemHeight - intrinsicHeight) / 2
                        val xMarkBottom = xMarkTop + intrinsicHeight
                        xMark.setBounds(xMarkLeft, xMarkTop, xMarkRight, xMarkBottom)
                    } else {
                        val xMarkLeft = itemView.left + xMarkMargin
                        val xMarkRight = itemView.left + xMarkMargin + intrinsicWidth
                        val xMarkTop = itemView.top + (itemHeight - intrinsicHeight) / 2
                        val xMarkBottom = xMarkTop + intrinsicHeight
                        xMark.setBounds(xMarkLeft, xMarkTop, xMarkRight, xMarkBottom)
                    }

                    xMark.draw(c)

                    super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                }
            }

        if (_recyclerView != null) {
            val touchHelper = ItemTouchHelper(itemTouchCallback)
            touchHelper.attachToRecyclerView(_recyclerView)
        }
    }


    override fun onBindViewHolder(holder: ViewHolder?, position: Int) {
        //
        if (position < 0 || position >= events.size || holder == null)
            return

        val event = events[position]

        if (eventsPendingRemoval.contains(event)) {
            // we need to show the "undo" state of the row
            holder.undoLayout?.visibility = View.VISIBLE
            holder.compactViewContentLayout?.visibility = View.GONE

            holder.undoButton?.setOnClickListener {
                v ->

                callback.onItemRestored(event)

//                val runnable = pendingEventRemoveRunnables[event];
                pendingEventRemoveRunnables.remove(event)

                eventsPendingRemoval.remove(event)

                notifyItemChanged(events.indexOf(event))
            }

        } else {
            holder.eventId = event.eventId;

            holder.eventTitleText.text = event.title

            if (useCompactView) {
                holder.undoLayout?.visibility = View.GONE
                holder.compactViewContentLayout?.visibility = View.VISIBLE

                val time = eventFormatter.formatDateTimeOneLine(event)
                holder.eventDateText.text = time
                holder.eventTimeText.text = ""

            } else {

                val (date, time) = eventFormatter.formatDateTimeTwoLines(event)

                holder.eventDateText.text = date
                holder.eventTimeText.text = time

                holder.eventLocatoinText?.text = event.location

                if (event.location != "")
                    holder.eventLocatoinText?.visibility = View.VISIBLE;
                else
                    holder.eventLocatoinText?.visibility = View.GONE;
            }

            if (event.snoozedUntil != 0L) {
                holder.snoozedUntilText?.text =
                    context.resources.getString(R.string.snoozed_until_string) + " " +
                        eventFormatter.formatSnoozedUntil(event);

                holder.snoozedUntilText?.visibility = View.VISIBLE;
                holder.snoozeButton?.text = changeString
            } else {
                holder.snoozedUntilText?.text = "";
                holder.snoozedUntilText?.visibility = View.GONE;
                holder.snoozeButton?.text = snoozeString
            }

            holder.calendarColor.color = if (event.color != 0) event.color.adjustCalendarColor() else primaryColor
            if (useCompactView)
                holder.compactViewCalendarColor?.background = holder.calendarColor
            else
                holder.eventTitleLayout?.background = holder.calendarColor
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup?, viewType: Int): ViewHolder? {
        val view = LayoutInflater.from(parent?.context).inflate(cardVewResourceId, parent, false);
        return ViewHolder(view);
    }

    override fun getItemCount(): Int = events.size

    val hasActiveEvents: Boolean
        get() = events.any { it.snoozedUntil == 0L }

    fun setEventsToDisplay(newEvents: Array<EventAlertRecord>)
        = synchronized(this) {
            events = newEvents;
            eventsPendingRemoval.clear()
            pendingEventRemoveRunnables.clear()
            notifyDataSetChanged();
        }

    fun getEventAtPosition(position: Int, expectedEventId: Long): EventAlertRecord?
        = synchronized(this) {
            if (position >= 0 && position < events.size && events[position].eventId == expectedEventId)
                events[position];
            else
                null
        }

    private fun getEventAtPosition(position: Int): EventAlertRecord?
        = synchronized(this) {
            if (position >= 0 && position < events.size)
                events[position];
            else
                null
        }


    fun removeEvent(event: EventAlertRecord)
        = synchronized(this) {
            val idx = events.indexOf(event)
            events = events.filter { ev -> ev != event }.toTypedArray()
            notifyItemRemoved(idx)
        }

    fun removeWithUndo(event: EventAlertRecord) {

        if (!eventsPendingRemoval.contains(event)) {

            eventsPendingRemoval.add(event);

            pendingEventRemoveRunnables.put(
                event,
                Runnable() {
                    synchronized(this) {
                        val idx = events.indexOf(event)
                        events = events.filter { ev -> ev != event }.toTypedArray()
                        notifyItemRemoved(idx)
                    }
                });

            synchronized(this) {
                notifyItemChanged(events.indexOf(event));
            }
        }
    }

    fun isPendingRemoval(position: Int)
        = eventsPendingRemoval.contains(getEventAtPosition(position))

    fun clearUndoState() {

        for ((event, runnable) in pendingEventRemoveRunnables)
            runnable.run()
        pendingEventRemoveRunnables.clear()
    }
}
