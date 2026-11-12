package app.resound.digest

class LlmInputLimitException(message: String = "内容超出所选模型的容量，请换用容量更大的模型") : IllegalArgumentException(message)
