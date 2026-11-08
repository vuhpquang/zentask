package com.vuhpquang.zentask.lists

import com.vuhpquang.zentask.model.ZenJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * A custom smart list's rule (stored as JSON in `Filter.rule`). Fields combine with AND, values inside
 * a field with OR. Unknown keys are ignored; a rule that can't be read matches nothing.
 * `due`: today (incl. overdue) | tomorrow | next7 (incl. overdue) | thisweek | nextweek | thismonth |
 * overdue | nodate | any (weeks start on Monday). `match`: all (fields AND, the default) | any (fields OR).
 */
@Serializable
data class FilterRule(
    val lists: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val priorities: List<Int> = emptyList(),
    val due: String? = null,
    val keyword: String? = null,
    val match: String = "all",
) {
    fun encode(): String = ZenJson.encodeToString(serializer(), this)

    companion object {
        fun parse(json: String): FilterRule? = try {
            ZenJson.decodeFromString(serializer(), json)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/**
 * How a list is shown (synced as a Preference with id `view.<key>`).
 * sort: date | priority | title | manual — group: date | priority | list | tag | column | none.
 * Empty values mean "the list's default".
 */
@Serializable
data class ViewOptions(
    val sort: String = "",
    val group: String = "",
    val showCompleted: Boolean = false,
) {
    fun encode(): String = ZenJson.encodeToString(serializer(), this)

    companion object {
        fun key(filterKey: String): String = "view.$filterKey"

        fun parse(json: String): ViewOptions = try {
            ZenJson.decodeFromString(serializer(), json)
        } catch (_: SerializationException) {
            ViewOptions()
        } catch (_: IllegalArgumentException) {
            ViewOptions()
        }
    }
}
