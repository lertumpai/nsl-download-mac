// Recheck existing full-download results in the actual app player, including seeks.
const {app,BrowserWindow}=require('electron')
const fs=require('fs'), path=require('path')
const root=path.resolve(__dirname,'../test-results/037')
app.setPath('userData',fs.mkdtempSync('/tmp/nsl-037-playback-'))
fs.writeFileSync(path.join(app.getPath('userData'),'config.json'),JSON.stringify({homepage:'about:blank'}))
app.commandLine.appendSwitch('autoplay-policy','no-user-gesture-required')
app.on('browser-window-created',(_,win)=>win.hide())
require('../main')
const sleep=ms=>new Promise(r=>setTimeout(r,ms))
app.whenReady().then(async()=>{
 const win=BrowserWindow.getAllWindows()[0], shell=win.webContents
 while(await shell.executeJavaScript('typeof openPlayer')!=='function')await sleep(100)
 const report=JSON.parse(fs.readFileSync(path.join(root,'report.json')))
 for(let i=0;i<report.results.length;i++){
  const result=report.results[i]
  if(!result.filePath||!result.fullDecode)continue
  try{
   win.showInactive()
   shell.setBackgroundThrottling(false)
   await shell.executeJavaScript(`openPlayer(${JSON.stringify(result.filePath)},${JSON.stringify(result.title)});document.getElementById('player-video').muted=true`)
   result.playbackChecks=[]
   for(const position of [0,Math.floor(result.duration/2),Math.max(0,Math.floor(result.duration-8))]){
    const observed=await shell.executeJavaScript(`(async()=>{
     const v=document.getElementById('player-video');v.currentTime=${position};await v.play();
     const end=Date.now()+15000;let start=v.currentTime;
     while(Date.now()<end){
      if(v.error)throw new Error(v.error.message);
      if(v.currentTime>${position+1}&&!v.paused&&v.videoWidth>0)return {position:${position},time:v.currentTime,width:v.videoWidth,height:v.videoHeight,decodedFrames:v.webkitDecodedFrameCount,readyState:v.readyState};
      await new Promise(r=>setTimeout(r,100));
     }
     throw new Error('Playback did not advance: '+JSON.stringify({time:v.currentTime,ready:v.readyState,paused:v.paused,error:v.error}));
    })()`)
    result.playbackChecks.push(observed)
    if(position>0&&position<result.duration-10)fs.writeFileSync(path.join(root,`playback-${i+1}.png`),(await shell.capturePage()).toPNG())
   }
   result.playback=result.playbackChecks[0]
   result.passed=true;delete result.error
   console.log('PLAYBACK PASS',result.title,JSON.stringify(result.playbackChecks))
  }catch(err){result.passed=false;result.error=err.message;console.error('PLAYBACK FAIL',result.title,err.message)}
  await shell.executeJavaScript('closePlayer()');win.hide()
 }
 report.playbackVerifiedAt=new Date().toISOString()
 fs.writeFileSync(path.join(root,'report.json'),JSON.stringify(report,null,2))
 app.exit(report.results.length===3&&report.results.every(r=>r.passed)?0:1)
}).catch(err=>{console.error(err);app.exit(1)})
