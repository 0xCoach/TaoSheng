package app.resound.digest

class DigestBatchLimitException : IllegalArgumentException("单段转写内容过长，请拆分后再整理")
