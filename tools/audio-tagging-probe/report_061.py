#!/usr/bin/env python3
"""从冻结参数和统计生成中文研究报告，不运行模型、不更改参数。"""
import argparse,csv,json,pathlib,collections,hashlib
P=argparse.ArgumentParser();P.add_argument('--root',type=pathlib.Path,default=pathlib.Path.home()/'Datasets/taosheng-audio');a=P.parse_args();R=a.root;O=R/'results';repo=pathlib.Path(__file__).resolve().parents[2]
f=json.loads((O/'frozen-config.json').read_text());t=json.loads((O/'tune-summary.json').read_text());v=json.loads((O/'validation-summary.json').read_text());dry=json.loads((O/'dry-replay-exploration.json').read_text());manifest=json.loads((R/'manifest.json').read_text());chosen=f['chosen'];c=f['configs'][chosen];s=v['validation'][chosen];p=v['phone'][chosen]
L=[]
def line(x=''):L.append(x)
def table(headers,rows):
 line()
 line('| '+' | '.join(headers)+' |');line('| '+' | '.join(['---']*len(headers))+' |')
 for row in rows:line('| '+' | '.join(str(x) for x in row)+' |')
 line()
def pc(x):return '—' if x is None else f'{x*100:.1f}%'
def pr(m):return pc(m['precision'])+'/'+pc(m['recall'])
def metricrow(name,z):
 m=z['skip'];return [name,pr(m),m['fp'],m['fn'],f"{z['live_skipped']}/{z['live_count']}",pr(z['media'])]
line('# 061 · 环境音识别评测与调优报告')
line('\n日期：2026-10-29。研究评测，仅本机运行；未改 App、未连接手机、未提交 Git。\n')
line(f'**结论：本轮调参选择 `{chosen}`，但没有足够证据证明可默认自动跳过录音。无配乐外放人声的真实场景漏洞未补上。** 环境标签可作为候选提示；外放自动过滤、重要声音通知均建议默认关闭。')
line('\n## 1. 数据与许可\n')
line('所有数据和逐条结果保存在 `~/Datasets/taosheng-audio/`，不在仓库中。下载脚本记录累计传输与 SHA-256，主下载 2,057,977,699 字节（2.058 GB），另有不足 1 MB 的目录及注释文本；远低于 8 GB。未下载 MUSAN 11 GB 全集。')
line()
table(['来源','许可与原始出处','本轮使用'],[
 ['ESC-50','[官方许可](https://github.com/karolpiczak/ESC-50/blob/master/LICENSE)：整体 CC BY-NC 3.0，逐条来源/作者另列','下载 2,000 条；选取 23 类 × 40 = 920 条原始音频，每条 5 秒'],
 ['MUSAN 音乐','[OpenSLR](https://www.openslr.org/17/)；[镜像](https://huggingface.co/datasets/corypaik/musan)。逐曲 CC BY / BY-SA，排除禁止演绎曲目','60 条原始音乐，13 个许可来源组；中部最多 45 秒；含有人声歌曲'],
 ['MUSAN 语音','LibriVox Public Domain；来源许可文件随数据保存','60 条多语种朗读，中部最多 45 秒；其中 4 条中文，非真人双向对话'],
 ['模拟房间/外放','本地生成 RIR；公开素材按其原许可处理，不分发混音','560 条派生：语音 5 种条件共 300、音乐外放 60、影视音效代理 200'],
 ['真实手机录音','负责人授权本机只读','两个目录共 20 个音频文件，按 segment ID 去重为 18 条；14 条日常语音、3 条已知媒体、1 条未知']])
line('逐曲作者、作品名和许可原文保存在 `manifest.json` 的 `attribution`；ESC-50 全部逐条署名在 `sources/ESC50-LICENSE`；数据版本锁定在 `sources/revisions.json`，下载哈希在 `download-ledger.json`。这不是将原始数据重新授权，研究结果不代表可把音频打包商用。')
line('\n**场景覆盖与缺口**\n')
table(['场景','数量','证据边界'],[
 ['安静语音 / 房间语音 / 嘈杂语音 / 背景音乐语音','各 60','只有第一类是原始朗读；后三类为模拟，嘈杂环境未覆盖真实饭店/咖啡馆/车内对话'],
 ['有配乐 / 无配乐外放语音','各 60 模拟；真实有配乐 3','无真实无配乐外放正例；不能验证新闻/访谈/短视频/有声书的真实回放识别'],
 ['纯音乐','60 原始 + 60 模拟外放','多种 FMA 风格，但不代表全部音乐风格'],
 ['环境音','23 类各 40','含交通、雨、流水、键盘、家电、猫狗等；无可靠独立厨房/空调/风扇/人群标签集'],
 ['婴儿哭 / 敲门 / 警笛 / 玻璃碎 / 狗叫','各 40 原始 + 40 模拟影视','影视代理是音效+朗读+配乐，不是实际电影音轨'],
 ['烟雾/火灾报警、门铃','0','不能以 clock_alarm 冒充烟雾报警；阈值 1.1 表示禁用，不声称验证通过']])
line('检索过 FSD50K、THCHS-30、MagicData-RAMC 等补充来源。FSD50K 本轮未完成所缺细类的逐条来源核验；THCHS-30 完整包约 6 GB，加现有数据接近/超过预算；[RAMC](https://www.openslr.org/123/) 完整包约 14 GB 且附 NC-ND 条款，官网入口需登录。本轮未下载这些数据；这是明确未满足的覆盖项，不是模型能力为零的证据。')
line('\n## 2. 划分、模拟与复现口径\n')
line('公开样本 1,600 条：调参 958，最终验证 642（40.1%）。ESC 按官方 fold 1–3 / 4–5 划分；MUSAN 原始文件按固定哈希分配，音乐按许可来源组隔离。每条混音的语音、音乐、噪声都来自同一分区，脚本断言所有 parent 均不跨区。公开原始素材 1,040 条，派生素材 560 条；派生条数不能当作独立样本数。LibriVox 缺少完整说话人/书籍身份，不能承诺严格说话人独立。')
line('调参脚本先只读取 tune 分数，冻结全部模型的阈值；单独 `--validate` 才统计 validation 和 phone。选择目标为跳过 F1、有正例的重要声音宏 F1、有正例的环境标签宏 F1 三者均值；跳过调参约束为真人语音代理误伤 ≤1%。媒体规则在每个真人语音代理子组上要求误报 ≤5%，否则弃权。验证结果不反向挑模型或改阈值。')
line(f'冻结时间：`{f["created_utc"]}`；配置 SHA-256：`{v["frozen_config_sha256"]}`。真实手机录音全部独立留作最终检查，`segment-142` 仅保留分数，不参与准确率。')
line('模拟：16 kHz 单声道，RMS 归一化到 0.08；随机指数衰减 RIR 的 RT60=0.25–0.65 秒，叠加 17/37/71 ms 反射。这是简化合成 RIR，未使用实测房间脉冲响应。外放再加四阶 300 Hz–5 kHz 带通及轻微 tanh 失真；真人加背景音乐的幅度比为 0.56（约 +5 dB 语音/音乐比）。这些参数固定，不利用验证分数修改。')
line('重要声音影视代理长 35 秒，5 秒音效放在 15–20 秒，混入语音和音乐再做外放处理。**这让中间抽窗天然覆盖音效，不能据此证明三段抽样不会漏掉随机时刻的重要声音。** 后续若要全天监测应优先连续滑窗，并补随机时刻及真实音轨验证。')
line('\n## 3. 三模型 × 窗口策略\n')
line('全部为 int8、CPU 2 线程、sherpa-onnx 2.0.1。sliding：10 秒窗、5 秒步长，末尾补齐一个完整尾窗，每类取最大值。three：开头/中间/末尾各 10 秒，重合窗去重。whole：Zipformer 整段。CED 不支持任意长整段，本项为不适用，未静默截断成“整段”。短于 10 秒的素材只推理一次；因此 920 条 ESC 原始音频不能体现窗口差异。')
line('P/R 表示查准率/查全率；误跳过=假阳性，漏跳过=假阴性。纯音乐和原始环境音是跳过正例；带语音媒体不属于这条跳过规则的正例。重要人声保护会主动牺牲婴儿哭等纯声音的跳过率。')
line('\n**调参集（958 条）**\n')
table(['模型/策略','跳过 P/R','误跳过','漏跳过','真人代理误伤','媒体 P/R'],[metricrow(k,z) for k,z in sorted(t.items())])
line('**封存验证集（642 条）**\n')
table(['模型/策略','跳过 P/R','误跳过','漏跳过','真人代理误伤','媒体 P/R'],[metricrow(k,z) for k,z in sorted(v['validation'].items())])
line('全组合的每类重要声音、标签、原始/模拟计时均另存 `results/tune-summary.json` 和 `results/validation-summary.json`，不只保留最佳模型。')
line('\n## 4. 推荐配置与验证结果\n')
line(f'离线候选：**{chosen}**；模型路径和模型哈希见 `results/*-provenance.json`。这是一套研究候选参数，不是已通过手机验收的生产配置。')
line('整段策略仅在本轮 5–45 秒公开片段上验证，不应直接推广到无限长全天录音。选择目标没有包含媒体 F1，因此此候选服务于过滤、环境标签和声音类别识别，不能视为三期功能全部通过。')
line()
line(f'- 语音分数 S = Speech、男女/儿童语音、Conversation、Narration、Whispering、Speech synthesizer 的最大值。人声保护 H = 婴儿哭、哭泣、笑、咳嗽、呼吸、尖叫、喊叫的最大值。\n- 自动跳过候选：`S < {c["skip_speech"]} 且 H < 0.05 且 max(Music, 环境标签映射及重要声音映射的全部类分数) ≥ {c["skip_content"]}`。未知/不确定时保留。\n- 疑似媒体候选：`S ≥ 0.05 且 (Music ≥ {c["media_music"]} 或 max(Television, Radio) ≥ {c["media_tv"]})`。只建议标记，不建议丢弃；若声纹认出本人，必须保留。声纹兜底本卡未测。\n- 阈值 `1.1` 为禁用哨兵（模型概率不超过 1），表示当前证据不支持可靠启用。')
line()
table(['验证场景','条数','跳过数','判媒体数'],[[k,z['n'],z['skip'],z['media']] for k,z in sorted(s['groups'].items())])
line(f'推荐组合在有配乐模拟外放中抓到 {s["groups"]["media_music"]["media"]}/24，无配乐模拟外放抓到 {s["groups"]["media_dry"]["media"]}/24，背景音乐真人代理误判 {s["groups"]["speech_music"]["media"]}/24；真实媒体组抓到 {p["groups"]["media_music"]["media"]}/3。为保护真人背景音乐场景，Music 分支被关闭；剩余 TV/Radio 分支未产生检出。本轮没有一套已证明可部署的三功能统一配置。')
line()
# Derive subgroup counts from existing scores only; never retune on validation.
model,strategy=chosen.split('/')
all_rows=[r for r in csv.DictReader(open(O/(model+'.csv'))) if r['strategy']==strategy]
meta={m['id']:m for m in manifest}
def maximum(r,names):return max(float(r.get(n,0)) for n in names)
content_names=list(set(sum(f['tags_mapping'].values(),[])+sum(f['events_mapping'].values(),[])))
def skip_row(r):return maximum(r,f['speech_mapping'])<c['skip_speech'] and maximum(r,f['human_guard'])<.05 and maximum(r,content_names)>=c['skip_content']
vr=[r for r in all_rows if meta[r['id']]['split']=='validation']
kindrows=[]
for kind in ['original','simulated']:
 rr=[r for r in vr if meta[r['id']]['kind']==kind];positive=[r for r in rr if meta[r['id']]['category'] in ['music','environment']];tp=sum(skip_row(r) for r in positive);fp=sum(skip_row(r) for r in rr)-tp
 kindrows.append([kind,len(rr),len(positive),tp,fp,pc(tp/(tp+fp) if tp+fp else None),pc(tp/len(positive))])
table(['素材组','验证条数','纯声音正例','正确跳过','误跳过','查准率','查全率'],kindrows)
line(f'真人语音代理误伤 {s["live_skipped"]}/{s["live_count"]}；真实手机语音误伤 {p["live_skipped"]}/{p["live_count"]}。即使零误伤也不能宣称总体 ≤1%：96 条代理来自 24 个语音源，同源版本相关；14 条真实语音即便全对且假设独立，单侧 95% 零失败上界仍约 19.3%。独立 299 条零失败才接近支持 1% 上界。')
line('\n**重要声音：原始验证集上的识别，与影视代理误触发分别统计**\n')
en={'baby':'婴儿哭','smoke':'烟雾/火灾报警','doorbell':'门铃','knock':'敲门','siren':'警笛','glass':'玻璃碎','dog':'狗叫'}
table(['声音','阈值','原始正例','P/R','漏报','原始负例误报','影视同类触发'],[[en[k],z['threshold'],z['raw']['positive'],pr(z['raw']),z['raw']['fn'],z['daily_false_alarms'],f"{z['film_same']['triggered']}/{z['film_same']['n']}"] for k,z in s['events'].items()])
line('重要声音阈值在调参原始集要求查准率 ≥90%，再选 F1 最优；无可用阈值就关闭。这里的“原始负例”含其他环境音、朗读、音乐，按素材主标签判定，不等于已逐秒人工复核的纯负例。音效能被认出不代表危险真实发生；影视触发是部署误报风险，不能归入真实告警成功。')
line('\n**环境标签归并与阈值**\n')
tagrows=[]
for name,z in s['tags'].items():
 rr=[r for r in vr if meta[r['id']]['kind']=='simulated'];ys=[name in (set(meta[r['id']]['tags']) | ({'音乐','人声活动'} if meta[r['id']]['category']=='media_event' else set())) for r in rr];ps=[maximum(r,f['tags_mapping'][name])>=z['threshold'] for r in rr];tp=sum(y and p for y,p in zip(ys,ps));pp=sum(ps);npos=sum(ys)
 simpr=pc(tp/pp if pp else None)+'/'+pc(tp/npos if npos else None)
 tagrows.append([name,'、'.join(f['tags_mapping'][name]),z['threshold'],pr(z['original']),z['original']['positive'],simpr,npos])
table(['用户标签','AudioSet 类（取最大）','阈值','原始 P/R','原始正例','模拟 P/R','模拟正例'],tagrows)
line('模拟标签以混音素材的已知成分为弱真值（影视音效代理补全其已知配乐和语音标签）；声音被遮盖后仍按含有该成分计正例，不等同于听感可辨认的人工标注。')
line('完整 527 类的逐类归并在 `results/label-map.csv`；未归并类明确标“忽略”。没有正例的室内/户外/车上/厨房/嘈杂人声等不作已验证标签；尤其车辆声音不能证明人坐在车内。标签阈值按原始主标签调 F1，不宜把分数直接展示为概率。')
line('\n## 5. 无配乐外放：未补上真实漏洞\n')
d=dry[chosen];line(f'只在 tune 搜索简单特征，再原样用于验证：TV/Radio 分数、低于 300 Hz 的能量比例 + 100 ms RMS 变异系数、能量活跃占比。选中规则 `{d["rule"]}`；调参 P/R={pr(d["tune"])}，模拟验证 P/R={pr(d["validation"])}，FP={d["validation"]["fp"]}，FN={d["validation"]["fn"]}；真实手机语音负例误报 {d["phone_negatives"]["fp"]}/{d["phone_negatives"]["n"]}。')
line('spectrum 规则的两个数字分别是低频能量比例上限和 RMS 变异系数上限；tv 为 TV/Radio 下限；active 为能量活跃占比下限。低频判据很容易识别我们自己加入的 300 Hz 带通，这只能说明能检出特定模拟处理。不同扬声器、电话人声、远场真人也会改变频谱；目前没有真实无配乐外放正例，故不能宣布识别来源。能量活跃占比不是 VAD 语音占比；本轮未跑 ASR，未估计真实语速。建议继续照常记录无配乐人声。')
line('\n## 6. 典型误判与私人录音核对\n')
examples=[]
for r in vr:
 m=meta[r['id']]
 if m['category']=='environment' and not skip_row(r):
  sp=maximum(r,f['speech_mapping']);hu=maximum(r,f['human_guard']);co=maximum(r,content_names)
  reason='人声保护触发，主动保留' if hu>=.05 else '语音分数超过跳过门槛' if sp>=c['skip_speech'] else '纯声音证据低于门槛'
  examples.append([r['id']+'.wav',f'{sp:.4f}',f'{hu:.4f}',f'{co:.4f}',reason])
 if len(examples)>=6:break
table(['公开文件名','S','H','纯声音最大分数','未跳过原因'],examples)
line('推荐组合在本轮验证音乐组没有漏跳过，也没有真人语音误伤；上表列环境音漏跳过的规则层原因。未进行逐条主观听审，不把原因猜测写成已确认的声学事实。')
line()
table(['公开文件 ID','类别','问题','分数'],[[e['id'],en[e['event']],e['type'],f"{e['score']:.4f}"] for e in s['event_examples']])
line('重要声音漏报表示当前阈值/类别定义不足；模拟影视触发说明声学标签本身不能判断声音是真实发生还是播放出来的。')
line()
table(['模型/策略','真实语音误跳过','真实媒体抓到'],[[k,f"{z['live_skipped']}/{z['live_count']}",f"{z['groups']['media_music']['media']}/3"] for k,z in sorted(v['phone'].items())])
line('私人录音仅列文件名与分数，未转写、未引用内容。未知文件也保留分数便于负责人复核：\n')
model,strategy=chosen.split('/');private=[]
for r in csv.DictReader(open(O/(model+'.csv'))):
 if r['id'].startswith('segment-') and r['strategy']==strategy:
  private.append([pathlib.Path(next(m['path'] for m in manifest if m['id']==r['id'])).name]+[f"{float(r[k]):.4f}" for k in ['Speech','Music','Television','Radio']])
table(['文件名','Speech','Music','Television','Radio'],private)
line('\n## 7. 耗时与手机估算\n')
line('本机 Apple M3、16 GB，模型均 2 线程；每次 accept_waveform+compute 计墙钟，排除模型加载、首次预热、解码、重采样、CSV 写出。同一窗口在不同策略复用分数；策略时间由其应调用窗口的实测耗时相加，非把复用后的总程序时间误当单策略耗时。一次运行，未重复测速，运行顺序可能受温度/系统负载影响。')
rows=[]
for key,z in sorted(v['validation'].items()):
 times=z['timing'];seconds=sum(x['audio_seconds'] for x in times.values());elapsed=sum(x['cpu_wall_seconds'] for x in times.values());rate=elapsed/seconds*60
 rows.append([key,f'{rate:.2f}',f'{times.get("original",{}).get("seconds_per_audio_minute",0):.2f}',f'{times.get("simulated",{}).get("seconds_per_audio_minute",0):.2f}',f'{rate*2.67:.2f}',f'{rate*1.5:.2f}–{rate*4:.2f}'])
table(['模型/策略','本机 秒/分钟','原始','模拟','手机粗估 ×2.67','手机假设范围 ×1.5–4'],rows)
line('手机估算方法：公开同版本 Geekbench 7.0 单核样本 [M3=2708](https://browser.geekbench.com/v7/cpu/570593)、[小米14 Pro=1016](https://browser.geekbench.com/v7/cpu/297145)，比值 2.67。把本机 2 线程耗时乘这个比值，只作预算估计；另给 ×1.5–4 敏感性范围。两条记录不是本设备实测，也不是 ONNX 性能基准，不能预测线程调度、INT8 内核、热降频、耗电或全天运行温度。真实手机录音的时长不同，单独计时见 JSON。未访问手机。')
wall=sum(json.loads(x.read_text())['wall_seconds'] for x in O.glob('*-provenance.json'));line(f'三模型实际推理阶段合计墙钟约 {wall/60:.1f} 分钟（缓存复用，不等于表中各策略相加）；数据准备和下载另计。')
line('\n## 8. 建议默认值、风险与下一步\n')
line('- 自动跳过默认关闭。可先只产生“可跳过”候选标签，保留原录音及找回入口；缺乏统计证据支持 ≤1% 真人误伤。\n- 影视默认照常记录；可选标记媒体，但不自动不转写。无配乐新闻、口播等继续保留。背景音乐真人对话必须作为持续反例。\n- 环境标签只启用本轮有正例支持的弱提示；无证据的场景推断默认关闭。\n- 重要声音默认关闭，只记候选记录，不作安全报警承诺；烟雾报警和门铃本轮无正例。影视音效造成的误报没有可靠消除。\n- 本轮未知的预训练数据重叠、主标签不完整、LibriVox 说话人重复、模拟房间与真实手机差异，都会使结果偏乐观。\n- 下张开发卡之前补真实中文对话、真实无配乐外放、随机时刻重要声音和烟雾/门铃数据；真人独立来源至少 299 条零误伤才可初步检验 1% 目标。当前研究完成了可复现基线，但完整场景覆盖及上线验收未通过。')
line('\n## 9. 复现与产物\n')
line('```bash\n# 使用现有 ~/.venvs/audiotag；依赖版本在 requirements-061.txt\nbash tools/audio-tagging-probe/run_061.sh\n# 已有下载与结果时，离线验证/生成报告（不重复已完成模型）\nbash tools/audio-tagging-probe/run_061.sh --offline\n```')
line('需要在允许写数据目录的会话执行；联网下载按单次提权。可用 AUDIO_TAG_DATA / AUDIO_TAG_MODELS / AUDIO_TAG_PYTHON 指定路径。脚本不操作 Git、不写 app/、不调用 adb。已冻结参数不自动重新调参；改变数据/模型应另开数据目录。')
line(f'完整分数：`{O}/zipformer.csv`、`ced-small.csv`、`ced-mini.csv`，每行含素材 ID、策略、耗时、全部 527 类分数；与 `manifest.json` 按 ID 联接。推荐参数：`{O}/frozen-config.json`；谱特征：`features.json`；分组统计：`tune-summary.json`、`validation-summary.json`；无配乐探索：`dry-replay-exploration.json`。')
(repo/'tools/audio-tagging-probe/recommended-061.json').write_text(json.dumps({'status':'research_only_defaults_off','model_strategy':chosen,'thresholds':c,'tags_mapping':f['tags_mapping'],'events_mapping':f['events_mapping'],'speech_mapping':f['speech_mapping'],'human_guard':f['human_guard'],'frozen_config_sha256':v['frozen_config_sha256']},ensure_ascii=False,indent=2)+'\n')
report=repo/'docs/任务卡/061-评测报告.md';report.write_text('\n'.join(L)+'\n');print(report)
