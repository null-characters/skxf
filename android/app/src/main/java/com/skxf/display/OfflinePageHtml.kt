package com.skxf.display

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 离线页面 HTML 渲染器
 * 仅负责渲染离线数据，状态切换由 Kotlin 端轮询处理
 */
object OfflinePageHtml {
    
    private const val ROWS_PER_PAGE = 19
    
    private fun formatSavedAt(ms: Long): String {
        if (ms <= 0) return "未知"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(ms))
    }
    
    private fun escapeHtml(str: String?): String {
        if (str == null) return ""
        return str.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
    
    fun render(
        tableJson: String,
        savedAtMs: Long,
        retryApiUrl: String
    ): String {
        val safeSavedAt = escapeHtml(formatSavedAt(savedAtMs))
        val safeRetryApi = escapeHtml(retryApiUrl)
        // JSON 需要安全注入到 JS，使用 quote 处理特殊字符
        val safeJson = JSONObject.quote(tableJson) ?: "null"
        
        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1.0,viewport-fit=cover,user-scalable=no"/>
<title>数据看板（离线）</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
::root{--brand:#FF5000;--glass-line:rgba(255,255,255,.28);--glass-fill:rgba(0,0,0,.18);--text:rgba(255,255,255,.96);--text-muted:rgba(255,248,240,.82)}
body{background:var(--brand);color:var(--text);font-family:'Microsoft YaHei',sans-serif;height:100vh;overflow:hidden;display:flex;flex-direction:column;padding:max(6px,env(safe-area-inset-top)) max(6px,env(safe-area-inset-right)) max(6px,env(safe-area-inset-bottom)) max(6px,env(safe-area-inset-left))}
.safe{flex:1;min-width:0;min-height:0;overflow:hidden;display:flex;flex-direction:column;align-items:center}
.safe-inner{flex:1;min-width:0;min-height:0;width:calc(100%/0.86);display:flex;flex-direction:column;transform:scale(.86);transform-origin:top center;margin-bottom:calc(-14%/.86*.86);height:calc(100%/.86)}
.header{background:linear-gradient(180deg,rgba(0,0,0,.12),rgba(0,0,0,.06));padding:14px 24px;border-bottom:2px solid var(--glass-line);display:flex;align-items:center;justify-content:center;position:relative;flex-shrink:0}
.header .time{font-size:15px;color:var(--text-muted);position:absolute;right:48px}
.header h1{font-size:28px;font-weight:700;color:#fff;letter-spacing:.04em;text-align:center;width:100%}
.table-box{flex:1;min-height:0;padding:6px 0 2px;overflow:hidden;display:flex;flex-direction:column}
.table-scroll{flex:1;min-height:0;overflow:hidden;border:1px solid var(--glass-line);border-radius:14px;background:var(--glass-fill);backdrop-filter:blur(10px)}
table{width:100%;border-collapse:collapse;table-layout:fixed}
th{padding:12px 10px;font-size:16px;font-weight:700;border-bottom:1px solid var(--glass-line);background:rgba(0,0,0,.22);color:#fff}
td{padding:12px 10px;font-size:15px;border-bottom:1px solid rgba(255,255,255,.12);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
tr:nth-child(even) td{background:rgba(0,0,0,.1)}
.cell-ok{color:#b9f6ca;font-weight:700}
.cell-late{color:#ffcdd2;font-weight:700}
.status{font-size:13px;padding:10px 18px;color:var(--text-muted);flex-shrink:0}
.offline-meta{position:fixed;left:10px;top:10px;font-size:12px;color:var(--text-muted);background:rgba(0,0,0,.18);border:1px solid rgba(255,255,255,.18);border-radius:10px;padding:10px 12px;max-width:min(520px,70vw)}
.offline-meta code{background:rgba(0,0,0,.22);padding:2px 6px;border-radius:6px}
</style>
</head>
<body>
<div class="offline-meta">
<div>● 离线中 | 快照: <code>$safeSavedAt</code></div>
<div style="margin-top:4px;opacity:.9">服务端: <code>$safeRetryApi</code></div>
</div>
<script>
const data=$safeJson;
let scrollIdx=0;
function esc(s){return s?s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'):''}
function statusCls(col,val){if(!col.includes('状态'))return'';const v=(val||'').trim();if(v==='正常')return'cell-ok';if(v==='延迟')return'cell-late';return''}
function render(){
const now=new Date();
const time=now.toLocaleString('zh-CN',{year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit'});
const title=(data&&data.title)||'数据看板';
const cols=(data&&Array.isArray(data.columns))?data.columns:[];
const rows=(data&&Array.isArray(data.rows))?data.rows:[];
const total=rows.length;
let h='<div class="safe"><div class="safe-inner"><div class="header"><h1>'+esc(title)+'<\/h1><div class="time">'+time+'<\/div><\/div><div class="table-box"><div class="table-scroll"><table><thead><tr>';
cols.forEach(c=>{h+='<th>'+esc(c)+'<\/th>'});
h+='<\/tr><\/thead><tbody>';
for(let i=0;i<$ROWS_PER_PAGE&&total>0;i++){
const r=rows[(scrollIdx+i)%total];
const d=Array.isArray(r.data)?r.data:[];
h+='<tr>';
d.forEach((cell,ci)=>{const cls=statusCls(cols[ci]||'',cell);h+='<td'+(cls?' class="'+cls+'"':'')+'>'+esc(cell)+'<\/td>'});
h+='<\/tr>';
}
h+='<\/tbody><\/table><\/div><\/div><\/div><\/div><div class="status">● 离线中（等待服务端） | '+((scrollIdx+1)+'-'+Math.min(scrollIdx+$ROWS_PER_PAGE,total)+'/'+total)+'<\/div>';
document.body.innerHTML=h;
}
render();
setInterval(()=>{const el=document.querySelector('.header .time');if(el)el.textContent=new Date().toLocaleString('zh-CN',{year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit'})},1000);
setInterval(()=>{const rows=(data&&Array.isArray(data.rows))?data.rows:[];if(rows.length>$ROWS_PER_PAGE){scrollIdx=(scrollIdx+1)%rows.length;render()}},5000);
</script>
</body>
</html>
        """.trimIndent()
    }
}
