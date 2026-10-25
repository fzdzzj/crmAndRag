# 涓?Agent 杞崲浜ゆ帴娲荤湅鏉?(Active Main Agent Handoff)

> **鏇存柊鏃堕棿**锛?026-10-25锛坕mage-slice-ingest-chain-reconciliation锛氬浘鍍忓垏鐗?*鍏ラ摼瀹屾暣鎬у璐?*钀藉湴鈥斺€斾竷閾捐璇佹槑 1:1锛屼簲绉嶉潤榛樹涪寮冭涔夎閽夋垚鏂█锛屽崱 26 鐨?5 涓己鍙ｆ敼鍒?`INCONCLUSIVE`锛?> **缁存姢濂戠害**锛?*鏈枃浠跺繀椤婚噰鐢ㄦ暣浣撹鐩栨洿鏂?(Rolling Overwrite)锛屼弗绂佹棤闄愯拷鍔犲巻鍙叉祦姘磋处**锛?> **閫傜敤瀵硅薄**锛?*鏂版墦寮€涓?Agent 瀵硅瘽绐楀彛鏃剁涓€蹇呰鍏ュ彛**銆傝瀹屽嵆鍙珛鍗宠繘鍏ユ寚瀵煎伐浣滅姸鎬併€?
---

## 1. 鐜扮姸蹇収 (Current State Snapshot)

- **褰撳墠涓诲共鐗堟湰**锛歚master` = **`977320a`锛圡1 鍚堝苟锛? `deac5ce`锛堝熀绾块噸閿氾級+ 鏈綊妗ｇ瑪** = origin/master銆傚垎鏀?`feature/eval-port-route-reconciliation` **姘镐箙淇濈暀**锛堝熀鐐?`d812ed1` 鈫?**2 绗?*锛歚0ffcd2c` 棣栬疆 / `fe5c76a` fix1 浠ｇ瑪锛?- **娴嬭瘯鍩虹嚎鍥涘厓缁?*锛歚source-revision=977320a`锛?*鏍戝噣锛岄潪 `-dirty`**锛? Surefire **鎶ュ憡 351 / 鐢ㄤ緥 2167 / F0 / E0 / S0** + Failsafe **鎶ュ憡 47 / 鐢ㄤ緥 149 / F0 / E0 / S16** + **CI = Run 40186225264锛?/6 jobs 缁匡級**銆備笂涓€鍗?349/2159/0 + 47/149/16 鈫?鏈崱 surefire **+2 鎶ュ憡 +8 鐢ㄤ緥**锛堝璐?3 + 瀹堝崼 5锛夛紝failsafe **涓夊€兼寔骞?*
- **鏈崱瑙勬ā**锛氬 `d812ed1` **7 鏂囦欢 / +1766 鈭?**锛堝叏鏂板锛夛紱merge 鏍?== 鍒嗘敮 tip 鏍戯紝鏍戝噣
- **鏈崱缁撹锛堜竴鍙ヨ瘽锛?*锛?*72 渚嬪浠剁殑鍒嗘暟涓嶈兘褰撱€屾枃鏈矾鐪熷垎鏁板垎甯冦€嶇敤銆?* `verdict=PARTIAL-OFF-PRODUCTION`锛屼笁鏉￠潪鐢熶骇鑵匡細杈撳叆鑵?`STUB`锛坄DeterministicEmbedding` 浼悜閲忥紝`RagQualityRegressionTest:278-283`锛夈€佺█鐤忚吙 `APPROXIMATION`锛堝瓧绗?bigram 椤舵浛 `FULLTEXT(ngram)`锛屾棤鎺堟潈 JOIN/杞垹锛宍SparseBenchmarkRecallService:33-63`锛夈€佸害閲忚吙 `SET-RELATIVE`锛坄DefaultWeightedReranker:41-42` 瀵规瘡鏌ヨ鍊欓€夐泦鍋?min-max锛夛紱**瑁呴厤鑵挎槸鐪熺敓浜х被**锛坄RagBenchmarkPipelineFactory:53-75`锛?- **骞崇Щ涓嶅彉鎬э紙鏈崱鍛介棬锛?*锛氬悓鍊欓€夐泦璺戜笁閬嶏紝鍚戦噺鍒嗘暣浣?`+100` 鏈€澶х粷瀵瑰樊 **2.83e-14**銆佹暣浣?`脳2` 宸?**0.0**锛涘崟鐐规姮楂樺鐓у悕娆?**1鈫?** 鈬?娴嬬殑鏄綊涓€鍖栥€佷笉鏄亽绛夊嚱鏁般€傜粨璁?`SET-RELATIVE-INVARIANT`锛氬垎鏁版槸闆嗗悎鍐呯浉瀵归噺锛岃法鏌ヨ/璺ㄨ鏂欐瘮杈冩棤鎰忎箟
- **瀵瑰偍澶?3 鐨勫缃紙Owner 鍐筹級**锛?*銆屼粠 72 渚嬪浠跺彇鍒嗗竷銆嶈繖鏉¤矾宸茶鍏虫帀**锛涘彲琛屽彛寰勫彧鍓?鈶?鍚屼竴鏌ヨ鍐呭€欓€夐棿**鍒嗗樊**鐨勫垎甯冿紝鈶?鐢熶骇搴撶湡璺戯紙闇€搴?+ 澶栧懠锛屽彟鍗★級
- **璐ｄ换鍒掑垎锛堜笉绮夐グ锛?*锛氬鏍?REJECT 鍒ゅ緱瀵光€斺€斿敮涓€闃绘柇椤规槸閿?MD 灏鹃儴绌鸿锛坄git diff --check` 鎶?`new blank line at EOF`锛夛紝鐢?`buildAnchorMd` 鏈熬绌轰覆鍏冪礌鐑よ繘鎻愪氦锛沠ix1 鎴戜唬绗旓細鐢熸垚鍣ㄧ骇鍒犲熬 + `assertFalse(md.endsWith("\n\n"))` 闃插洖褰?+ 璁㈡浠诲姟鍗°€岄敋鍐呭０鏄庛€嶆帾杈烇紙璇ュ０鏄庡彧鍦ㄤ换鍔″崱锛岄敋 JSON 鏃犳瀛楁锛?- **宸ヤ綔鏍戞嫇鎵?*锛氳瀵熸爲 `D:\code\crmAndRag`锛坙ogbook + `work/` 鏈窡韪級锛涙潈濞佹爲 `D:\code\crmAndRag-merge-add-knowledge-admin-api`锛堝垎鏀?/ 闂ㄧ / 鍚堝苟 / push 鍞竴娉曞畾鏍戯級

---

## 2. 鏍稿績鏋舵瀯涓庣幇琛岃兘鍔涢潰

1. **鏉冮檺鍩熶笁鏀跺彛**锛歚PermissionModule` 鍙锋鍗曚竴浜嬪疄婧?+ 鎺堟潈椤?UX + 鏉冮檺瀛楅潰閲忕被鍨嬬敓鎴愶紙`permission.gen.ts` 婕傜Щ鍗崇孩锛?2. **濂戠害鑷甫鏉冮檺缁达紙鍏ㄩ摼闂幆锛?*锛氬悗绔?`@RequirePermission` 鈫?OpenAPI `x-required-permission{code,name}` / `x-permission-status: OPEN`锛?79 operation锛屼笌 `PermissionCoverageScanner` **闆嗗悎鐩哥瓑**锛夆啋 鍓嶇 `endpointPermission.gen.ts`锛坱ype-only锛夆啋 `uiEndpointPermissions.ts` 灏忚〃锛堢紪璇戞湡 `satisfies` 閽夋锛夆啋 `Permission.vue` 鐨?`endpoint` prop锛堟湭鐧昏 **fail-closed**锛?3. **杩戦噸澶嶅浘鍍忥細妗ｄ綅 + 浜斾唤璇佹嵁 + 涓ゅ紶鍏ュ簱闈㈤敋**锛氱敓浜?`[0.982, 0.9944)` 涓夋。闄嶆潈 `脳0.75/脳0.6/脳0.5`锛屽唴杈圭晫浠?`BAND_REFERENCE_FLOOR=0.9887` 鍐荤粨锛坄0.982 | 0.9906 | 0.9925 | 0.9944`锛夛紱浜斾欢濂?*鍏ㄩ儴銆屾祴浜?0.9887銆?*锛涚鏀?`near-dup-floor-signoff-v1`锛涘叆搴撻潰 = S0 闂搁棬鎺㈤拡锛?/6 杩囬椄銆?8 娆″鍛笺€佸喕缁撴姇褰憋級+ S1 NOT-RUN 璐熺粨璁?4. **鍥惧儚鍒囩墖鎽勫彇閾撅細宸茬敱鏂█瀹堟姢**锛堟湰鍗★級锛氫竷閾鹃€愰摼瀵硅处锛坄image-slice-ingest-chain-v1`锛? 浜旂闈欓粯涓㈠純璇箟 + `embedText` 纰版挒鍥涘洜瀛愭潯浠讹紙鏂囦欢鍚嶁埀绫荤洰鈭ч〉鐮佲埀鎻忚堪锛夛紱`stageImageVector` 浠?*鍦?* `if (description != null)` 鍒嗘敮鍐咃紙`PdfImageSliceIngestService:248`锛夆噿 闂搁棬鎷掑浘杩炲甫涓㈠浘鍍忓悜閲忥紝**杩欐槸琚祴閲忕殑鐜拌薄锛屼笉鏄緟淇?bug**
5. **鍥剧墖鍥涘紑鍏抽棬鎺у叧绯讳笌鐑敓鏁堝凡瀹炶瘉**锛歚image-slice-ingest.enabled` + `kb-allowlist` 闂ㄦ帶鍦ㄥ墠銆乣image-vector-ingest.enabled` 鍦ㄥ悗锛涘洓閿繍琛屾湡鐑敓鏁堛€佸啓鍥炲嵆鍋滐紙`ImageVectorSwitchHotFlipIT` 涓冩潯鏂█锛?6. **寮€鍏冲紑鍚€氶亾宸插伐鍏峰寲**锛歚image-vector-ingest-preflight.sh`锛圙O/NO-GO + 鍘熷洜鐮侊級锛屼笌瑙傛祴闈€佸洖婊氥€佺儹鐢熸晥瀹炶瘉鎴愬
7. **鍥惧儚璺嫭绔嬬浉浼煎害纭椄闂?*锛堢嫭绔嬮敭 0.9944 + 涓夋。 tier + 鍑嗗叆涓嬮檺 0.982 + 甯﹀唴鍩哄噯 0.9887 鍐荤粨 + 閲嶆帓鍚庛€佽瀺鍚堝墠闄嶆潈锛?8. 鍏朵綑宸查棴鐜兘鍔涢潰锛堝妯℃€佺湡鍏变韩绌洪棿 / v6.0 涓诲浠剁湡璺?/ PDF 鍥炬枃璇勬祴 / 浜旇矾璇箟 hook / 鍏捣 CAS / 鍐欑鐐规牎楠?/ 鍚戦噺杩愮淮鏀跺彛 / 绾跨储婕忔枟 V42 / 瓒呯 token 瑙嗗浘绛夛級娌跨敤涓婁竴鐗堬紝涓嶅啀澶嶈堪
9. **杩戦噸澶嶆秷铻嶏細S0 闆跺鍛?A/B 宸插疄娴?*锛堝崱 28锛夛細姝ｅ鐓ц瘉鏄?*瑁呯疆鏈夐壌鍒姏**锛屽喕缁撶湡瀹炶鏂?7 鏌ヨ A/B 寰?`verdict=NO-RANK-CHANGE`锛堝垎鏁颁綅绉?0.06銆佸悕娆′笌闆嗗悎闆跺彉鍖栥€乬ap 鈮?绐楀彛锛夛紱閿?`near-dup-ablation-v1`锛堝啓妯″紡 `-Dnear.dup.ablation.write=true`锛岄粯璁ら€愬瓧鑺傚璺戙€侀浂澶栧懠銆丆I 鐪熻窇銆乫ailsafe 鐢ㄤ緥鏁?+1锛夛紱S1锛堥潪閫€鍖栬繎閲嶅璇枡锛屸墹36 娆″鍛硷級**浠呴娉ㄥ唽鏈墽琛?*
10. **璇勬祴绔彛鍙ｅ緞宸插璐?*锛堝崱 29锛夛細72 渚嬪浠剁殑 `verdict=PARTIAL-OFF-PRODUCTION`鈥斺€旇閰嶈吙鐢熶骇銆佽緭鍏ヨ吙浼悜閲忋€佺█鐤忚吙 bigram 杩戜技銆佸害閲忚吙 min-max 闆嗗悎鍐呭綊涓€锛涘钩绉讳笉鍙樻€у疄娴嬶紙`+100` 宸?`2.83e-14`銆乣脳2` 宸?`0.0`銆佸崟鐐瑰鐓у悕娆?`1鈫?`锛夆噿 **鍏跺垎鏁颁笉寰楀綋銆屾枃鏈矾鐪熷垎鏁板垎甯冦€嶇敤**锛涢敋 `eval-port-route-v1`锛堝啓妯″紡 `-Deval.port.route.anchor.write=true`锛屽畧鍗?`EvalPortRouteGuardTest`锛?
---

## 3. 褰撳墠鍦ㄩ€斾笌鍊欓€夐槦鍒楋紙image-slice-ingest-chain-reconciliation 闂幆鍒锋柊锛?
- **鍦ㄩ€斿緟鍚堝叆鍗?*锛?*0 寮?*锛?*宸茬珛鍗″緟娲惧彂**锛?*0 寮?*锛?*寰呭姙姹?*锛?*0 鏉?*
- **闀挎湡鍌ㄥ锛堟寜浼樺厛绾э級**锛?  1. **S1 娑堣瀺鎵ц锛堟湰鍗￠娉ㄥ唽锛孫wner 鍐筹級**锛氶潪閫€鍖栬繎閲嶅璇枡锛堝師鐢熷垎杈ㄧ巼閲嶇紪鐮?/ 杞诲害 JPEG / 鏁村浘寰缉锛岀煭杈逛粛 鈮?4px锛夛紝鍏ュ簱鍚?*鏂█瀹炴祴杩囬椄鏁?== 棰勬湡鏁?*锛屼换浣曢潤榛樹涪寮冨嵆绾紱鎴愭湰绠楀紡 `6脳3 + 6脳2 = 30`锛?*鎶ユ壒 36 灏侀《**銆傝Е鍙戞潯浠跺凡鍐欒繘閿氾細鏈崱鍒?`NO-RANK-CHANGE` 涓旂獥鍙ｇ畻鏈樉绀虹湡瀹炶鏂?*鏃犵獥鍐呭叡鐜?*锛坓ap 0.3856 vs 绐楀彛 0.15锛?  2. **瑙傛祴鐪熷疄浣嶇Щ鍚庡啀璁?0.90锛圤wner 鍐筹級**锛歚0.90` 鏄?`R=94.17%` 鑶濈洊鐐癸紝浣嗗鍘?6 寮犲悓鏂囨。鍥撅紱鍐嶅姩 `NEAR_DUPLICATE_FLOOR` **蹇呴』鏂板崱**
  3. ~~鏂囨湰璺湡鍒嗘暟鍒嗗竷鍙ｅ緞鏍搁獙~~ **宸茬敱鍗?29 缁撴锛堝叧闂級**锛?2 渚嬪浠剁殑鍒嗘暟鏄泦鍚堝唴鐩稿閲忥紙min-max锛? 浼悜閲?+ bigram 杩戜技绋€鐤忚吙 鈬?涓嶅彲褰撳垎甯冦€傝鍒嗗竷鍙墿 鈶?鍚屼竴鏌ヨ鍐?*鍒嗗樊**鍒嗗竷 鈶?鐢熶骇搴撶湡璺戯紙闇€搴?澶栧懠锛屽彟鍗★級锛岀敱 Owner 瀹?  4. **鐢ㄦ埛鎰忓浘绾х浉鍏虫€ф爣娉紙鍞竴鑳芥秷鎺?A1/A2 鐨勮矾锛?*锛氶』鐪熷疄鏌ヨ + 浜烘爣锛?*涓ョ**鐢?agent 鐢熸垚鏍囩鍏呮暟
  5. **鐢熶骇寮€鍏宠繍琛屾湡鎵撳紑锛圤wner 鍐筹紝闈炰唬鐮侊級**锛歚rag.retrieval.image-vector-ingest.enabled` 榛樿 `false`锛涘厛璺?`bash scripts/rag-quality/image-vector-ingest-preflight.sh preflight --snapshot <鍥涢敭蹇収>`
  6. **鍓嶇灏忚〃闅忕珯鐐瑰闀跨淮鎶?*锛歚uiEndpointPermissions.ts` 鎵嬪啓 57 椤癸紝缂栬瘧鏈熷彈濂戠害绾︽潫
  7. 鏂囨。绫诲瓨閲忥細鍏ㄥ煙鏉冮檺娓呭崟锛坄docs/permission-matrix-audit.md` 灞?*鍘嗗彶蹇収锛岄伒瑁佸喅涓嶆敼鍐?*锛夛紱鍦ㄧ嚎鑰冭瘯瀛愬煙 `work/_online-exam-backlog.md`锛涘€欓€夋€昏处 `work/_paa-candidates-pool-v2.md`
- **涓嬫壒鎺ㄨ崘**锛歊AG 璐ㄩ噺绾跨殑**闆跺鍛奸宸插熀鏈竻瀹?*鈥斺€旇涔堣姳 30 娆″鍛煎仛鍌ㄥ 1锛圫1 娑堣瀺锛岄』 Owner 鍗曠嫭鎺堟潈锛夛紝瑕佷箞杞埆鐨勫煙锛堝偍澶?6 鍓嶇灏忚〃 / 鍌ㄥ 7 鏂囨。瀛橀噺锛夈€?*涓嶈**涓轰簡銆屾湁浜у嚭銆嶅啀寮€涓€寮犲彧璇讳唬鐮佸氨鑳借嚜绛旂殑鍙栬瘉鍗?
---

## 4. 涓?Agent 鏍稿績鎵ц濂戠害涓庡崗浣滅孩绾?
1. **瑙掕壊杈圭晫**锛氫富 Agent 璐熻矗渚﹀療銆佹灦鏋勩€佸啓鍗°€佸鏍歌鍐炽€丮1/M2 鏀跺彛锛?*涓嶅啓涓氬姟浠ｇ爜**锛堣瘉鎹敋鐨勬帹瀵兼簮鐮佸睘渚嬪锛岃绗?21 鏉★級
2. **纭孩绾?*锛氫弗绂佹湭缁?Owner 鎺堟潈 `git push` / 鏀瑰啓鍘嗗彶 / 鍒犲垎鏀?/ 鐢熶骇澶栧懠 / 鏀瑰簱缁撴瀯锛?*绂佹敼宸插悎鍏ヨ縼绉昏剼鏈?*
3. **浠诲姟鍗℃淳鍙?*锛歚work/README.md` 鐩爣绾︽潫鍨嬬煭鍗★紱鎻愮ず璇嶅啓姝诲啓闆嗐€佷慨澶嶄笂闄?1 娆°€佸洖鎶?鈮?00 瀛椼€佺粏鑺傜暀鏂囦欢锛涙墽琛屼笌澶嶆牳瀛?Agent **涓€寰?Owner 浜叉淳**锛?*浠诲姟瑕佹眰涓庡啓闆嗗繀椤诲榻?*
4. **澶嶆牳寮哄害鍒嗘。**锛坄docs/review-intensity-policy.md`锛夛細C 妗ｅ diff锛汢 妗ｉ棬绂?0 + 瀹?diff锛汚 妗ｏ紙杩佺Щ/鏉冮檺/鐪熻窇/**濂戠害**锛夌嫭绔嬪鏍稿洓鍒ゆ嵁
5. **鍚堝苟闂幆绾緥**锛歚merge --no-ff` 淇濈暀鍒嗘敮 鈫?**鏍戝搱甯岄€愬瓧鑺傚垽绛?* 鈫?**骞插噣鏍戜笂 `mvn clean verify` + `--update`锛堝彧璺戜竴娆★級閲嶉敋** 鈫?push 鈫?CI **run 绾?`conclusion`** 鍏ㄧ豢 鈫?M2 褰掓。
6. **銆岃窇涓嶄簡銆嶇粨璁轰竴寰嬫寕 CI 鏉′欢**锛氭湰鍦拌涓嶄笅鐨勬瀯寤?鐪熻窇涓嶅緱鐩存帴銆屾帴鍙椼€嶏紝椤荤敱 CI 瀵瑰簲 job 瀹氭
7. **鏄犲皠/鐢熸垚鐗╅搧寰?*锛氬垎绫婚€昏緫鍏堣瘉鏁版嵁渚у疄闄呭舰鎬侊紱**鍔ㄦ墜鏀归€犲墠鍏堟煡濂戠害/缁撴灉瀵硅薄鏄惁宸插甫鎵€闇€瀛楁**锛涚敓鎴愮墿蹇呴』鐢遍粯璁よ窇鐨勬牎楠屾祴璇曞畧浣?8. **璇佹嵁绾緥**锛氳ˉ鍕句緷鎹瘡涓暟瀛楀繀椤昏兘鐙珛澶嶇畻锛涘熀绾?`source-revision` 蹇呴』鏃?`-dirty`锛?*棰勬敞鍐岄棬妲涘繀椤婚攱鍒?*锛?*銆屾棤涓€澶?/ 鍏ㄩ儴 / 榛樿涓€鑷淬€嶈繖绫诲叏绉版柇瑷€蹇呴』鍚屽洖鍚堥檮鍙绠楁竻鍗?*
9. **鍕鹃€夊彧鏈変笁鎬佷笖璇箟鍗曚竴**锛歚[x]`=瀹屾垚銆乣[-]`=鎸夎瀹氫笉鍋氥€乣[ ]`=鏈仛锛涖€屼笉鍋氥€嶄笉璁歌 `[x]`锛涘垽鎹綔搴熷悗椤荤粰鎸囧悜宸插垹鏂囦欢鐨勫畬鎴愰」琛ャ€屽凡鍐呰仈/宸叉媶闄?+ 鐣欐。 SHA銆嶈拷璁?10. **鏁版嵁璐ㄩ噺鍏堜簬缁撹**锛氥€屼笉鍙兘浜嬩欢銆嶅厛鎬€鐤戠敓鎴愮绾匡紝鍐嶈皥缁熻
11. **閲忕翰鐧藉悕鍗?*锛氫换浣曘€屽崟璋冩€?鍒嗗竷銆嶇粨璁哄繀椤诲啓鏄?*閲忕翰 + 鍙ｅ緞 + 鏉ユ簮琛?*
12. **鍒ゆ嵁鍗冲悎鍚?*锛氬垽鎹袱鏀彲閲嶅彔銆佹垨鍑虹幇鏈柟鍚戞棤鎰忎箟鐨勮瘝锛屽綋鍦烘秷姝у苟闄勫彲鎵ц鏂█锛涗簨鍚庢秷姝у彧鑳芥湞**鏇翠繚瀹?*鏂瑰悜
13. **琛屽熬鍗冲垽鎹?*锛氫細鍋?*閫愬瓧鑺傛瘮瀵?*鐨勯敋/鐢熸垚鐗╋紝搴忓垪鍖栧繀椤婚拤姝?LF锛圝ackson 榛樿缂╄繘鍣ㄨ蛋 `System.lineSeparator()`锛?14. **澶嶆牳 PASS 鈮?鍙氦浠?*锛歚clean verify` 蹇呴』鍦?*閲嶆柊鐗╁寲鍚庣殑鍚堝苟鏍?*涓婅窇
15. **鐜闄烽槺**锛氫腑鏂?UTF-8 鏂囦欢閫愯璁℃暟鐢?`read`锛涗腑鏂?`sed/grep` pattern 杩涘懡浠よ鏄?127锛沚ash 璧?`D:\git\Git\bin\bash.exe -lc`锛沗Set-Content -Encoding utf8` 浼氬啓 BOM锛汫itHub 鎸?`needs` 瀹屾垚鎵嶅垱寤轰笅娓?job锛岄棴鍖呬互 run 绾?`conclusion` 涓哄噯
16. **閿氬瓧娈垫瘮杈冨宸』涓庢柇瑷€鍚屽宸?*
17. **閿氭潯鐩』鍙嫭绔嬭В璇?*锛氫細琚氦鍙夊紩鐢ㄧ殑鏁扮粍瀛楁锛屾瘡鏉¤嚜甯﹀垽璇绘墍闇€鐨勫竷灏?鑼冨洿鏍囧織
18. **涓婄晫椤绘敞鏄庤鏀剧缉鐨勯噺鍙婂叾鍊煎煙**
19. **鎭掑畾鍊间竴鏀癸紝鍏堥棶銆岃皝璺熺潃瀹冪畻銆?*锛歡rep 鍑烘墍鏈夌敱瀹冩帹瀵肩殑鍏紡涓庤竟鐣岋紱鎶娿€屽噯鍏ラ潰銆嶄笌銆岃绠楀熀鍑嗐€嶆媶鎴愪袱涓父閲忚鍙樻洿**绾姞鎬?*锛涜瘉鎹晶涓€寰嬪喕缁撳瓧闈㈤噺
20. **璺ㄦ爲璺戝懡浠わ紝鍏堥棶瀹冭惤鍦ㄥ摢妫垫爲**锛氬繕甯?`workdir` 浼氳惤鍒拌瀵熸爲 detached 鏃ф彁浜わ紝銆屽懡浠や笉鍙鐜般€嶅叏鏄嚜宸遍€犵殑鍋囪薄锛涜法鏍戝懡浠ゅ繀椤绘樉寮?`workdir`锛屽鏍哥粨璁哄墠鍏?`git rev-parse --short HEAD` 鑷瘉钀界偣
21. **鍒ゆ嵁涔嬮棿瑕佹湁閫傜敤鎬ч棴鐜紙鍗?27 鏂板锛?*锛氬垽鎹?A 鍒ゃ€屾満鍒惰兘鍚﹀鐜般€嶏紝鍒ゆ嵁 B 鍒ゃ€岀幇璞¤兘鍚﹀綊鍥犮€嶏紝浜岃€?*涓嶆槸鍚屼竴浠朵簨**锛涘嚒鍦?B 閲屽紩鐢?A 鐨勬満鍒讹紝蹇呴』鐢?*鏈崱鑷繁浜у嚭鐨勫墠鎻愭潯浠?*鍥炲ご鑷涓€娆★紙鍗?27 `r4` 鐨勬潯浠跺綋鍦哄惁璇佷簡 `r5` 鐨勫綊鍥狅級銆傚彟涓€鏉″悓婧愶細**鏀广€岀敱娴嬮噺鍊兼満姊版帹瀵煎啓鍏ャ€嶇殑璇佹嵁閿氱殑缁撹锛屽繀椤绘敼鎺ㄥ婧愶紝涓嶈兘鏀规垚鍝?*鈥斺€斿彧鏀规垚鍝佺孩鐨勬槸澶嶇畻娴嬭瘯锛屾瀬鏄撹璇垽鎴愩€岄敋琚汉鎵嬫敼鍧忋€?22. **鍒ゅ畾鏍囩涓嶅緱瀵硅緭鍏ユ亽鐪燂紙鍗?28 鏂板锛?*锛歷erdict / 缁撹瀛楁蹇呴』鑳藉彇鍒扮浉鍙嶅€尖€斺€旀妸銆屽垎鏁板繀鐒跺彉銆嶄笌銆岄『搴忓彲鑳藉彉銆嶅苟杩涘悓涓€涓爣绛撅紝鏍囩灏遍€€鍖栨垚甯哥湡寮忥紝涓嬫父鍙細璇诲埌鍋囦綅绉汇€傛淳鐢熺粨璁?*鍙敱鍒ゅ畾闈㈡帹瀵?*锛屾暟鎹潰鍗曞垪瀛楁锛堝崱 28 `scoreFace`锛夛紱鍐欏垽鎹椂鍏堣嚜闂€屼粈涔堣緭鍏ヤ細璁╁畠鍙栧弽銆嶏紝绛斾笉鍑哄氨璇存槑瀹冧笉鏄垽鎹?23. **寮曠敤瑕佹寚鍒拌兘 grep 鐨勫疄浣擄紙鍗?29 鏂板锛?*锛氬嚒鍐欍€岄敋鍐呭０鏄庛€嶃€屽凡鐧昏銆嶃€岃鏂囨。銆嶈繖绫诲紩鐢紝蹇呴』鑳藉湪琚寚瀵硅薄閲?grep 鍒拌瀛楁/璇ヨ鈥斺€斿崱 29 浠诲姟鍗″０绉扮殑銆岄敋鍐呭０鏄庛€嶅湪閿?JSON 閲屾牴鏈笉瀛樺湪锛堝鏍告帰閽?`decl-independentImpl=false` 褰撳満璇佷吉锛夈€傚悓婧愪竴鏉★細**鐢熸垚鐗╃被闂ㄧ锛堝 `git diff --check` 鐨勫熬閮ㄧ┖琛岋級蹇呴』鐢辩敓鎴愬櫒鑷韩鏂█瀹堜綇**锛屼簨鍚庢鏌ュ彧鑳芥姄涓€娆★紝闃蹭笉浣忓洖褰?
---

## 5. 鏉冨▉绱㈠紩鎸囪矾 (Where to Look)

- 鎺ュ彛涓庡喕缁撳绾︼細`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`
- 鏁版嵁搴撹縼绉伙細`ls src/main/resources/db/migration`锛堝疄娴嬫渶楂?**V43**锛屼笅涓€鍙敤 **V44**锛涙渶杩戜袱鍗￠浂杩佺Щ锛?- 鍘嗗彶瑁佸喅褰掓。锛歚docs/archive/logbook-historical-202609-202610.md`锛涙椿鍔ㄦ棩蹇?`docs/main-agent-logbook.md`锛?*鏈€鏂?搂179**锛?- 鍘嗗彶浠诲姟鍗★細`work/archive/closed-cards/`锛涙湰鍗″崱闈?`openspec/changes/archive/eval-port-route-reconciliation/`
- 鍥炲綊鍩虹嚎锛歚bash scripts/check-test-baseline.sh`锛堝彴璐?`scripts/test-baseline.txt`锛屽彧缁?`--update` 鍐欙級
- **杩戦噸澶嶅浘鍍忓叚浠介敋**锛歚near-dup-exchange-rate-v1`锛堜环鐩〃锛夛紡`near-dup-fusion-impact-v1`锛堣瀺鍚堜笂鐣岋紝`-Dnear.dup.fusion.write=true`锛夛紡`image-near-dup-truth-v1`锛堟绱㈢骇鐪熷€硷紝`-Dnear.dup.truth.write=true`锛夛紡`near-dup-displacement-evidence-v1`锛堜綅绉诲疄娴嬶紝`-Dnear.dup.displacement.write=true`锛夛紡`image-variant-cross-matrix-v1`锛堜唬鐞嗘湁鏁堟€э紝`-Dimage.cross.matrix.write=true`锛夛紡`near-dup-floor-signoff-v1`锛堢鏀跺喅璁紝瀹堝崼瀹炵畻鍏敋 SHA锛?- **鍏ュ簱闈笁閿?*锛歚near-dup-ingest-gate-probe-v1`锛圫0 闂搁棬鎺㈤拡锛屽喕缁撴姇褰憋級锛廯near-dup-ingest-s1-not-run-v1`锛圫1 NOT-RUN 棰勬敞鍐岋紝瀹堝崼鏂█ S1 閿?*蹇呴』涓嶅瓨鍦?*锛夛紡`image-slice-ingest-chain-v1`锛堜竷閾惧璐?+ 浜旂涓㈠純璇箟 + 纰版挒鍥涘洜瀛愶紝鍐欐ā寮?`-Dimage.slice.chain.anchor.write=true`锛?- **娑堣瀺閿氾紙鏈崱锛?*锛歚near-dup-ablation-v1`锛圫0 A/B锛屽啓妯″紡 `-Dnear.dup.ablation.write=true`锛岄粯璁ら€愬瓧鑺傚璺?+ 闆跺鍛硷紝瀹堝崼 `NearDupAblationGuardTest`锛岀粨璁轰綅 `verdict` 鍙敱鍚嶆闈?闆嗗悎闈㈡帹瀵笺€佸垎鏁伴潰鍗曞垪 `scoreFace`锛?- **鐪熻窇 opt-in**锛歚RAG_BENCHMARK_REAL=1` / `RAG_VISION_EMBED_REAL=1` / `RAG_NEAR_DUP_DISPLACEMENT_REAL=1` / `RAG_IMAGE_CROSS_MATRIX_REAL=1` / `RAG_NEAR_DUP_INGEST_REAL=1`锛?*璺宠繃 鈮?閫氳繃**
- 濂戠害/浣撶Н闂ㄧ锛歚scripts/check-openapi-consistency.sh`銆乣frontend/bundle-budget.json`锛涜閲忎笌瓒呯瑙嗗浘锛歚docs/token-metering.md`

---

## 6. 涓?Agent 杞崲浜ゆ帴鎿嶄綔鎸囧紩 (Handing Over Protocol)

- **缁撴潫浼氳瘽鍓嶅繀椤诲仛**锛氣憼 鏈夋柊鍗″悎鍏ユ垨鍩虹嚎鍙樺姩鍒欐洿鏂?搂1/搂2/搂3锛涒憽 浠诲姟鍗″綊妗ｅ埌 `work/archive/closed-cards/`锛涒憿 涓ョ杩藉姞娴佹按璐︼紝鍏ㄦ枃 鈮?20 琛屾暣浣撹鐩栥€?- **鏂颁富 Agent 绗竴鍔ㄤ綔**锛氣憼 璇?`AGENTS.md` + 鏈枃浠讹紱鈶?`git status --short` 鏍稿疄涓ゆ５鏍戯紱鈶?绔嬪嵆鍝嶅簲闇€姹傘€?