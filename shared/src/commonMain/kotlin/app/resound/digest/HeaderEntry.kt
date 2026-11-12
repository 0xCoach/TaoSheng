package app.resound.digest

data class HeaderEntry(val name: String, val value: String) {
    override fun toString(): String = "HeaderEntry(name=$name)"
}
