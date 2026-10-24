"""用本机 Gradle 缓存的 Kotlin 编译器跑真正 Kotlin 实现，再逐值和 KNF 比。"""
from pathlib import Path
import subprocess, json
import numpy as np
from probe import ROOT, features, unit
import onnxruntime as ort
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
artifacts=[('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.20'),('org.jetbrains.kotlin','kotlin-stdlib','2.2.10'),('org.jetbrains.kotlin','kotlin-script-runtime','2.2.20'),('org.jetbrains.kotlin','kotlin-reflect','2.2.10'),('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.10.2'),('org.jetbrains','annotations','23.0.0')]
jars=[]
for group,name,version in artifacts:
    matches=list((cache/group/name/version).glob('*/*.jar'))
    if not matches: matches=list((cache/group/name).glob('*/*/*.jar'))[-1:]
    jars += matches
cp=':'.join(map(str,jars)); java='/opt/homebrew/opt/openjdk@21/bin/java'
source=ROOT/'KaldiFbank.kt'
if not source.exists(): source=ROOT.parents[1]/'app/src/main/java/app/resound/voiceprint/KaldiFbank.kt'
out=ROOT/'private/kotlin';out.mkdir(parents=True,exist_ok=True)
subprocess.run([java,'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(out),str(source),str(ROOT/'FbankCli.kt')],check=True)
opts=ort.SessionOptions();opts.intra_op_num_threads=1;opts.inter_op_num_threads=1
session=ort.InferenceSession(str(ROOT/'model.onnx'),sess_options=opts)
def embedding(f):
    return unit(session.run(None,{'x':f[None]})[0][0])
report={}
for pcm in sorted((ROOT/'fixtures').glob('*.pcm')):
    target=out/(pcm.stem+'.fbank')
    subprocess.run([java,'-cp',str(out)+':'+cp,'FbankCliKt',str(pcm),str(target)],check=True)
    actual=np.fromfile(target,'<f4').reshape(-1,80);expected=np.fromfile(pcm.with_suffix('.fbank'),'<f4').reshape(-1,80)
    delta=np.abs(actual-expected)
    normalized=out/(pcm.stem+'.normalized')
    subprocess.run([java,'-cp',str(out)+':'+cp,'FbankCliKt',str(pcm),str(normalized),'normalized'],check=True)
    actual_cmn=np.fromfile(normalized,'<f4').reshape(-1,80)
    expected_cmn=expected-expected.mean(axis=0,keepdims=True)
    normalized_delta=np.abs(actual_cmn-expected_cmn)
    report[pcm.stem]=dict(values=actual.size,max_abs=float(delta.max()),mean_abs=float(delta.mean()),normalized_max_abs=float(normalized_delta.max()),embedding_cosine=float(embedding(actual_cmn)@embedding(expected_cmn)))
    assert delta.max()<0.002 and normalized_delta.max()<0.002,report
    assert report[pcm.stem]['embedding_cosine']>0.99999,report
(ROOT/'results/fbank.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
