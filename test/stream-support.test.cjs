const test = require('node:test')
const assert = require('node:assert/strict')
const {isStreamHlsMaster,is037Page,isKnownVideoAd,selectStream,requestHeaderArgs,streamDownloadArgs,lineReader}=require('../src/stream-support')
const master='https://master.streamhls.com/hls/efa6cc6509680b4eb4dbb1f75d2e8e91/master'
test('extensionless movie master is recognized without matching arbitrary hosts',()=>{
 assert.ok(isStreamHlsMaster(master));assert.ok(!isStreamHlsMaster(master.replace('streamhls.com','streamhls.com.evil.test')))
 assert.ok(is037Page('https://www.037hddmovies.com/movie/'));assert.ok(!is037Page('https://037hddmovies.com.evil.test/'))
 assert.ok(isKnownVideoAd('https://cdend.com/ad.mp4'))
})
test('movie master replaces a preroll and survives rendition requests',()=>{
 const ad={url:'https://example.test/ad.mp4',type:'Video'}
 const movie={url:master,type:'HLS',headers:{referer:'https://player.stream1689.com/'}}
 assert.equal(selectStream(ad,movie),movie)
 assert.equal(selectStream(movie,{url:'https://example.test/720/index',type:'HLS'}),movie)
 assert.equal(selectStream(movie,{...movie,headers:{origin:'https://player.stream1689.com'}}).headers.referer,movie.headers.referer)
 assert.equal(selectStream(movie,{url:'blob:https://example.test/id',type:'Video'}),movie)
})
test('uses captured embedded frame referrer and origin, with no guessed origin',()=>{
 assert.deepEqual(requestHeaderArgs('https://movie.test/','https://stream.test/',{Referer:'https://embed.test/',Origin:'https://embed.test','User-Agent':'actual browser'},'fallback'),['--user-agent','actual browser','--referer','https://embed.test/','--add-header','Origin: https://embed.test'])
 assert.deepEqual(requestHeaderArgs('https://movie.test/','https://stream.test/',{},'ua'),['--user-agent','ua','--referer','https://movie.test/'])
})
test('completion paths survive chunk boundaries and trailing data',()=>{
 const lines=[];const reader=lineReader(s=>lines.push(s))
 reader.push('NSL_FI');reader.push('LE:"/tmp/movie.mp4"\n[download] 5');reader.push('0%');reader.end()
 assert.deepEqual(lines,['NSL_FILE:"/tmp/movie.mp4"','[download] 50%'])
})

test('PNG-prefixed TS demuxing is restricted to this provider and the merger',()=>{
 assert.deepEqual(streamDownloadArgs(master),['--postprocessor-args','Merger+ffmpeg_i:-f mpegts'])
 assert.deepEqual(streamDownloadArgs('https://example.test/movie.m3u8'),[])
})

test('canonical supported video pages retain their extractor',()=>{
 for(const movie of [
  {url:'https://vk.com/video-123_456',type:'Video'},
  {url:'https://www.youtube.com/watch?v=123',type:'YouTube'},
  {url:'https://x.com/example/status/123',type:'Twitter'},
 ]) assert.equal(selectStream(movie,{url:'https://cdn.test/master.m3u8',type:'HLS'}),movie)
})
