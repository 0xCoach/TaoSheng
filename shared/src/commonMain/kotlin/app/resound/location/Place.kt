package app.resound.location

const val MANUAL_PLACE_SOURCE = "手动"

data class Place(val name: String, val address: String, val source: String, val home: Boolean = false) {
    val manual: Boolean get() = source == MANUAL_PLACE_SOURCE
}
