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
 * @param {{ gatewayOk: boolean, piwebOnlyRestarts: number }} state
 * @returns {'piweb' | 'stack'} `piweb` = 只 kill 监听 30141 的进程树（launcher 会自己把它拉起来，
 *   网关与 frp 隧道全程不断）；`stack` = 连 launcher 一起回收重建。
 */
function planRecovery({ gatewayOk, piwebOnlyRestarts }) {
  // 网关自己都不答了，说明问题不在 pi-web 那一层（同一份 launcher 拉起来的），整栈重来。
  if (!gatewayOk) return 'stack';
  if (piwebOnlyRestarts >= PIWEB_ONLY_RESTARTS_BEFORE_FULL) return 'stack';
  return 'piweb';
}

module.exports = { PIWEB_ONLY_RESTARTS_BEFORE_FULL, planRecovery };
