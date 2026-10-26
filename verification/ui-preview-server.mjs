// Isolated, read-only layout harness. It has no valid application token and no write API.
import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
const root=path.resolve('src/main/resources/static');
const preview=path.resolve('build/ui-previews/responsive.html');
const mime={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json; charset=utf-8'};
http.createServer(async(req,res)=>{
 try{
  const url=new URL(req.url,'http://127.0.0.1:18894');
  if(req.method!=='GET'){res.writeHead(405);return res.end('Read-only layout preview');}
  if(url.pathname.startsWith('/api/')){
   res.setHeader('Content-Type','application/json');
   if(url.pathname==='/api/config')return res.end(JSON.stringify({version:'2.1.1-rebuild.4-java8',java:'1.8.0_504',targetJava:'8',springBoot:'2.7.18',targetMybatis:'2.3.2',token:'LAYOUT_PREVIEW_NOT_A_VALID_TOOL_TOKEN'}));
   if(url.pathname==='/api/history/page')return res.end('{"items":[],"total":0}');
   if(url.pathname==='/api/regression/config')return res.end('{"allowedTargets":[]}');
   return res.end('[]');
  }
  const relative=decodeURIComponent(url.pathname==='/'?'/index.html':url.pathname);
  const file=url.pathname==='/responsive.html'?preview:path.resolve(root,'.'+relative);
  if(file!==preview&&!file.startsWith(root+path.sep)){res.writeHead(400);return res.end();}
  const bytes=await fs.readFile(file);res.setHeader('Content-Type',mime[path.extname(file)]||'application/octet-stream');res.end(bytes);
 }catch(error){res.writeHead(404);res.end('Not found');}
}).listen(18894,'127.0.0.1',()=>console.log('Read-only responsive layout harness on 18894'));
