const test = require('node:test')
const assert = require('node:assert/strict')
const vm = require('node:vm')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const {EventEmitter}=require('node:events')
const {PassThrough}=require('node:stream')

async function runDownload(exitCode, writeOutput) {
 const folder=fs.mkdtempSync(path.join(os.tmpdir(),'nsl-completion-'))
 const handlers=new Map(), events=[]
 let args
 const electron={
  app:{whenReady:()=>({then(){}}),on(){},getPath:()=>folder},
  ipcMain:{handle:(name,handler)=>handlers.set(name,handler),on(){}},
  session:{fromPartition:()=>({getUserAgent:()=> 'test-browser',cookies:{get:async()=>[]}})},
  Notification:class {show(){}},
 }
 const proc=new EventEmitter();proc.stdout=new PassThrough();proc.stderr=new PassThrough()
 const sandbox={require:name=>{
  if(name==='electron')return electron
  if(name==='electron-store')return class {constructor(){this.store={saveFolder:folder}}get(k){return this.store[k]}}
  if(name==='child_process')return {spawnSync:()=>({stdout:''}),spawn:(_,a)=>{args=a;return proc}}
  if(name==='./src/stream-support')return require('../src/stream-support')
  return require(name)
 },__dirname:path.resolve(__dirname,'..'),process,console,setTimeout,setInterval,clearInterval,setImmediate,URL}
 vm.createContext(sandbox)
 vm.runInContext(fs.readFileSync(path.join(__dirname,'../main.js'),'utf8'),sandbox)
 sandbox.testSend=(channel,data)=>events.push({channel,data})
 vm.runInContext('mainWindow = {webContents:{send:testSend}}',sandbox)
 try {
  await handlers.get('ytdlp:download')(null,{pageURL:'https://example.test/movie.m3u8',formatSelector:'best',title:'test',outputFormat:'mp4'})
  const output=args[args.indexOf('--output')+1].replace('%(ext)s','mp4')
  if(writeOutput)fs.writeFileSync(output,'fixture video bytes')
  proc.stdout.write('NSL_FI');proc.stdout.write(`LE:${JSON.stringify(output)}\n`)
  proc.emit('close',exitCode)
  return {events,args}
 } finally {fs.rmSync(folder,{recursive:true,force:true})}
}
test('completed output uses after_move path and required complete-file flags',async()=>{
 const {events,args}=await runDownload(0,true)
 assert.equal(events.filter(e=>e.channel==='download:done').length,1)
 assert.ok(args.includes('--abort-on-unavailable-fragments'))
 assert.ok(args.includes('--remux-video'))
 assert.ok(events.find(e=>e.channel==='download:done').data.filePath.endsWith('/test.mp4'))
})
test('nonzero exit with an existing partial file stays failed',async()=>{
 const {events}=await runDownload(1,true)
 assert.ok(events.some(e=>e.channel==='download:failed'))
 assert.ok(!events.some(e=>e.channel==='download:done'))
})
test('zero exit without an output file stays failed',async()=>{
 const {events}=await runDownload(0,false)
 assert.ok(events.some(e=>e.channel==='download:failed'))
 assert.ok(!events.some(e=>e.channel==='download:done'))
})
