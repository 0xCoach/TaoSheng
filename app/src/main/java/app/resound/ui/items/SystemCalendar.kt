package app.resound.ui.items

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import app.resound.digest.ItemStatus
import app.resound.digest.MemoryItem
import app.resound.digest.TodoDraft
import app.resound.todo.CalendarDraft
import app.resound.todo.CalendarDraftRules
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toKotlinLocalDate
import java.time.LocalDate

/**
 * 加到手机日历：打开系统日历的“新建日程”页面并填好内容，用户点保存才算加上。
 * 不申请日历权限，涛声自己不写日历；之后待办改了，日历里那条不会跟着变。
 */
internal object SystemCalendar {
    private fun zone() = TimeZone.currentSystemDefault()

    fun open(context: Context, draft: CalendarDraft): Boolean {
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, draft.title)
            .putExtra(CalendarContract.Events.DESCRIPTION, draft.notes)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, draft.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, draft.end)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, draft.allDay)
        // 弹出面板里拿到的是包了一层的 Context：找回所在页面再打开，不另开新任务（红米上首次跳转偶有失败）
        val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.firstOrNull { it is android.app.Activity }
        val launcher = activity ?: context
        if (launcher !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { launcher.startActivity(intent); true }
        catch (_: ActivityNotFoundException) {
            // 手机上没有日历应用：提示即可，不算出错
            Toast.makeText(context, "手机上没有可用的日历应用", Toast.LENGTH_SHORT).show(); false
        }
    }

    /** 有日子直接打开；没日子先选日子，再按全天日程打开 */
    fun add(context: Context, item: MemoryItem) {
        CalendarDraftRules.from(item, zone())?.let { open(context, it); return }
        val today = LocalDate.now()
        DatePickerDialog(context, { _, y, m, d ->
            open(context, CalendarDraftRules.forDay(item, LocalDate.of(y, m + 1, d).toKotlinLocalDate(), zone()))
        }, today.year, today.monthValue - 1, today.dayOfMonth).apply { setTitle("加到哪一天") }.show()
    }

    /** 待办确认卡上的草稿：转成一条临时条目再走同一套规则 */
    fun add(context: Context, draft: TodoDraft) = add(context,
        MemoryItem(0, null, draft.category, draft.kind, draft.content, draft.dueText, draft.dueAt, draft.who, ItemStatus.Accepted, null))
}
