package app.resound.envsound

inline fun <reified T: Enum<T>> names(value: String): Set<T> = value.split(',').mapNotNull { name -> enumValues<T>().firstOrNull { it.name == name } }.toSet()
