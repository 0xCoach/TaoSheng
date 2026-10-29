package app.resound.envsound

/** 卡 061/070 冻结规则；分数只是模型证据，不是发生概率。 */
object EnvSoundRules {
    const val speechLimit = .2f
    const val humanLimit = .05f
    const val contentLimit = .2f
    const val mediaSpeech = .05f
    const val mediaMusic = .9f
    val speech = listOf("Speech", "Male speech, man speaking", "Female speech, woman speaking", "Child speech, kid speaking", "Conversation", "Narration, monologue", "Whispering", "Speech synthesizer")
    val human = listOf("Baby cry, infant cry", "Crying, sobbing", "Laughter", "Cough", "Breathing", "Screaming", "Shout")
    val tags = linkedMapOf(
        EnvTag.Music to (.8f to listOf("Music")),
        EnvTag.Keyboard to (.02f to listOf("Computer keyboard", "Typing")),
        EnvTag.Rain to (.2f to listOf("Rain", "Rain on surface", "Raindrop")),
        EnvTag.Pet to (.1f to listOf("Dog", "Bark", "Cat", "Meow", "Purr")),
        EnvTag.Water to (.05f to listOf("Water", "Water tap, faucet", "Pour", "Drip")),
        EnvTag.Appliance to (.005f to listOf("Vacuum cleaner", "Air conditioning", "Mechanical fan")),
        EnvTag.Footsteps to (.02f to listOf("Walk, footsteps")),
        EnvTag.AlarmClock to (.01f to listOf("Alarm clock")),
        EnvTag.Traffic to (.3f to listOf("Vehicle", "Traffic noise, roadway noise", "Car", "Train", "Aircraft", "Helicopter", "Vehicle horn, car horn, honking", "Siren")))
    val sounds = linkedMapOf(
        SpecialSound.BabyCry to (.05f to listOf("Baby cry, infant cry")),
        SpecialSound.Knock to (.3f to listOf("Knock")),
        SpecialSound.DogBark to (.1f to listOf("Dog", "Bark")),
        SpecialSound.Siren to (.3f to listOf("Siren", "Police car (siren)", "Ambulance (siren)", "Fire engine, fire truck (siren)")))
    data class Decision(val verdict: SegmentVerdict, val reason: String?, val tags: Set<EnvTag>, val sounds: Set<SpecialSound>, val skip: Boolean, val markMedia: Boolean)
    fun decide(scores: Map<String, Float>, settings: EnvSoundSettings, me: Boolean): Decision {
        fun max(names: List<String>) = names.maxOfOrNull { scores[it] ?: 0f } ?: 0f
        val s = max(speech); val h = max(human); val music = scores["Music"] ?: 0f
        // 不完整/非法输出不能以缺省零分触发过滤。
        val valid = (speech + human + tags.values.flatMap { it.second } + sounds.values.flatMap { it.second }).all { scores[it]?.let { v -> v.isFinite() && v in 0f..1f } == true }
        val media = settings.enabled && valid && !me && s >= mediaSpeech && music >= mediaMusic
        val skip = settings.enabled && valid && !me && settings.skip != SkipMode.Off && s < speechLimit && h < humanLimit && max(tags.values.flatMap { it.second } + sounds.values.flatMap { it.second }) >= contentLimit
        val verdict = when { media -> SegmentVerdict.Media; skip && settings.skip == SkipMode.On -> SegmentVerdict.Skipped; skip -> SegmentVerdict.Skippable; else -> SegmentVerdict.Keep }
        return Decision(verdict, when(verdict) { SegmentVerdict.Media -> "疑似影视"; SegmentVerdict.Keep -> null; else -> if(music >= contentLimit) "音乐" else "环境音" },
            if(settings.enabled && settings.tags && valid) tags.filter { max(it.value.second) >= it.value.first }.keys else emptySet(),
            if(settings.enabled && settings.specialSounds && valid && !media) sounds.filter { max(it.value.second) >= it.value.first }.keys else emptySet(),
            verdict == SegmentVerdict.Skipped || media && settings.media == MediaMode.SkipTranscribe, media && settings.media == MediaMode.MarkMedia)
    }
    fun windows(samples: Int): List<IntRange> {
        require(samples > 0)
        if(samples <= 30 * 16000) return listOf(0 until samples)
        val width = 10 * 16000
        return listOf(0, (samples-width)/2, samples-width).distinct().map { it until it+width }
    }
}
