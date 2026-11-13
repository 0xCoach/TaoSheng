package app.resound.topic

import kotlinx.datetime.toKotlinLocalDate

/** 安卓调用方的旧日期入口，日期只作机械转换。 */
internal fun RoomTopicRepository.observeDay(date: java.time.LocalDate) = observeDay(date.toKotlinLocalDate())
internal fun TopicRow.retainedIds(): List<Long> = org.json.JSONArray(retainedItemIds).let { a -> (0 until a.length()).map { a.getLong(it) } }
