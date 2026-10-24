package app.resound.ui.skills

import app.resound.AppGraph
import app.resound.skills.SkillRepository
import app.resound.ui.preview.FakeSkillRepository

private val fake by lazy { FakeSkillRepository() }

/** 技能后端装配好就用真的，否则用假数据（卡 031 合并前） */
fun skillRepo(): SkillRepository = if (AppGraph.isSkillsReady) AppGraph.skills else fake
