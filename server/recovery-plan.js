// 「连败 N 次之后该动哪一层」的纯逻辑。
//
// 单独成文件和 crash-forensics.js / startup-probe.js 一样的原因：supervisor.js 一被
// require 就会跑 main()，测试引不进来；而这条判定有真实代价（判错就是手机端多断几分钟），
// 不能只靠读代码确认。
//
// 背景（2026-11-11）：pi-web 进程活着但事件循环卡死，探针连超 3 次，守护把**整栈**
// kill 掉重来 —— launcher 重建 pi-web + 网关 + frp，实测 12:13:19 判死、12:15:40
// 才重新 Ready，手机端白等 2 分半。而卡死只在 pi-web 那一层，网关当时一直是 200。

'use strict';

// 先只换 pi-web 子树，连续这么多次还没恢复才升级到整栈回收。
// 2 次 = 给 launcher「5s 后自动重启 pi-web」两轮机会，足够排掉偶发卡顿。
const PIWEB_ONLY_RESTARTS_BEFORE_FULL = 2;

/**
 * @param {{ gatewayOk: boolean, piwebOnlyRestarts: number, hasPiWebOwner?: boolean }} state
 * @returns {'piweb' | 'stack'} `piweb` = 只 kill 监听 30141 的进程树（launcher 会自己把它拉起来，
 *   网关与 frp 隧道全程不断）；`stack` = 连 launcher 一起回收重建。
 */
function planRecovery({ gatewayOk, piwebOnlyRestarts, hasPiWebOwner = true }) {
  // 网关自己都不答了，说明问题不在 pi-web 那一层（同一份 launcher 拉起来的），整栈重来。
  if (!gatewayOk) return 'stack';
  // 30141 上根本没有监听进程时（如 launcher 误跳过了启动），killTree(null) 只会空转浪费时间，直接重建整栈。
  if (!hasPiWebOwner) return 'stack';
  if (piwebOnlyRestarts >= PIWEB_ONLY_RESTARTS_BEFORE_FULL) return 'stack';
  return 'piweb';
}

/**
 * 探测不健康时是否应忽略（不累计失败、不触发回收）：
 * - `hasRestartTimer`: 子进程已退出、正在等退避定时器重启，此时端口不通是预期状态；
 *   继续计数会在 50s 后误入 `!this.child` 分支当成无主孤儿栈强行拉起，击穿 60s/300s 退避。
 * - `hasChild && childElapsedMs < bootGraceMs`: 刚拉起的实例仍在冷启动预算内。
 */
function shouldIgnoreDownProbe({ hasRestartTimer, hasChild, childElapsedMs, bootGraceMs }) {
  if (hasRestartTimer) return true;
  if (hasChild && childElapsedMs < bootGraceMs) return true;
  return false;
}

module.exports = { PIWEB_ONLY_RESTARTS_BEFORE_FULL, planRecovery, shouldIgnoreDownProbe };
