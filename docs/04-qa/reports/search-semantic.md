# QA Report: search-semantic

> File: `docs/04-qa/reports/search-semantic.md` — Owner: QA
> Nguồn story: `docs/02-ba/stories/search-semantic.md` (STORY-search-semantic) — Status: Approved
> Nguồn spec: `docs/03-dev/specs/search-semantic.md` + `-query.md` (SPEC-search-semantic) — Status: Approved
> Ngày review: 2026-10-11
> Commit/branch reviewed: main @ 8a24890 + working tree chưa commit (file search-semantic nằm trong thay đổi chưa commit)

## Kết luận

**PASS WITH ISSUES**

Toàn bộ AC-1…AC-8 đạt. Không có lỗi chặn. Test đều xanh (backend 732 unit + 520 IT/verify, web 447 test, tsc/lint sạch). Phạm vi/ACL kín (filter trong `knn`, double-check Postgres, `sed` bị loại ở cả hai lớp). Còn một số vấn đề non-blocking (xem dưới) và các điều kiện go-live theo spec (hiệu chỉnh `min_score` bằng tập vàng) chưa thực hiện được ở bước này.

## Đối chiếu Acceptance Criteria

| AC | Kết quả | Bằng chứng |
|---|---|---|
| AC-1 / 1.1 / 1.2 (luôn hybrid, không chọn chế độ) | Pass | `SemanticSearchRequest` không có `mode`; `SemanticSearchService.java:151-176` luôn chạy song song vector + lexical; `HybridFusion.java:30-90` RRF k=60, không rerank. FE `page.tsx` không có control chọn chế độ; test `page.test.tsx:103` ("has NO control to force semantic-only or keyword-only"), IT `clientSuppliedModeOrMinScoreFieldsAreIgnored`. Dùng lại `SearchFilterPanel` (`page.tsx:300`). |
| AC-1.3 (gợi ý /search) | Pass | `page.tsx:34-35,285-290` copy nguyên văn + `Link href="/search"`; test `page.test.tsx:103`. Truy vấn dài >32 từ được nhận (IT `aLongNaturalLanguageQuestionOver32WordsIsAccepted`). |
| AC-1.4 (route + menu R1/R2) | Pass | `features.ts:107-115` key `semantic-search`, roles CAN_BO+LANH_DAO; `/search` thêm `activeExcludePrefixes` (chỉ để menu không active đôi, trang `/search/page.tsx` không đổi); test `features.semantic.test.ts`, `page.test.tsx:96` (R3 bị từ chối). |
| AC-2 / 2.1 / 2.2 | Pass | `SemanticResultHydrator.java:177-190` trả excerpt từ `content_segment.current_text` + tài liệu + vị trí + `confirmSource`; `SemanticResultCard.tsx` badge vàng đúng nguyên văn, tiêu đề "Các đoạn nội dung liên quan" (`page.tsx:27`); không có từ "câu trả lời". Test `page.test.tsx:117,134`; IT `returnsTheCallersOwnPassageWithCitationAndNoModeLabel`. |
| AC-2.3 (không highlight, nhãn "Khớp từ khoá") | Pass | Không có field highlight trong `SemanticResult`; nhãn ở `SemanticResultCard.tsx` (`matchedLexical`); test `page.test.tsx:134`. |
| AC-2.4 (nội dung mới nhất) | Pass | Excerpt đọc từ Postgres, không từ chỉ mục (`SemanticResultHydrator.java:152`); IT `afterAnEditTheNextSearchShowsTheNewTextNotTheOldOne`. Đường ghi: poller phát `embed.requested` + `SemanticIndexingIT` (10 test xanh), `SemanticIndexReconciliationJobTest`. |
| AC-2.5 (tóm tắt video, không `sed`) | Pass | Loại `sed` ở 2 lớp: filter `must_not content_source=sed` trong knn (`KnnSegmentSearcher.java:68-69`) và hydrator (`SemanticResultHydrator.java:149-151`); nhãn "Tóm tắt nội dung video" + "Mở tài liệu từ đầu" (`SemanticResultCard.tsx`); test `page.test.tsx:155`, IT `summaryIsReturnedWithItsSourceAndSedNeverAppearsEvenWithAMatchingVector`. |
| AC-3 / 3.1 / 3.2 / 3.3 (rỗng, không hạ ngưỡng) | Pass | `HybridFusion.java:68-81` giữ ứng viên chỉ khi `matchedLexical` hoặc `cosine >= minScore`; không có đường hạ ngưỡng, request không nhận `minScore`; rỗng ⇒ 200 + `insufficientEvidence` (`SemanticSearchService.java:194`), UI rỗng trung tính không banner (`page.tsx:344-348`); `HybridFusionTest`, IT `noEvidenceIsAnOrdinary200...`, `aVectorBelowTheThresholdIsNotReturnedEvenWhenItIsTheNearestOne`, FE `page.test.tsx:185`. |
| AC-4 (lọc phạm vi) / AC-4.1 (hồi quy rò rỉ) | Pass | Xem mục "Kiểm tra rò rỉ quyền" bên dưới. IT `userBCannotPullUserAsUnsharedPassage...`, `theScopeFilterIsInsideTheKnnClause...` (80 decoy hoàn hảo của A không che mất đoạn của B), `whenTheIndexAclHasDrifted...`, `aGroupShareMakes...`. |
| AC-4.2 (403 fail-closed) | Pass | `SemanticSearchService.java:119-129` ném 403 `SCOPE_UNRESOLVED` trước khi gọi engine/embedding, có audit; FE `page.tsx:176` banner đỏ nguyên văn; IT `whenTheGroupLookupFailsTheRequestIs403AndNoEngineOrEmbeddingEndpointIsCalled`; FE `page.test.tsx:201`. |
| AC-4.3 (hai lớp) | Pass | Lớp 1: `KnnSegmentSearcher.buildRequest` + `SearchQueryBuilder.buildFilterClauses`; lớp 2: `SemanticResultHydrator.java:86-93` `BusinessDataScope.forDocuments` + `id IN`. Cả hai nhánh (lexical và vector) đều qua hydrator. |
| AC-5 / 5.1 (suy giảm một nhánh) | Pass | `SemanticSearchService.java:162-174` null = nhánh lỗi ⇒ `lexical_only`/`semantic_only`; scope filter vẫn áp dụng (cùng `ctx`); `SemanticSearchServiceTest` (lexical_only, semantic_only), IT `whenTheEmbeddingEndpointFailsTheAnswerIsLexicalOnlyAndStillScoped`, `anEmbeddingVectorOfTheWrongDimensionDegrades...`. |
| AC-5.2 (503) | Pass | `SemanticSearchService.java:165-171` 503 `SEARCH_UNAVAILABLE`; FE xoá kết quả cũ (`page.tsx:174-175`); `bothBranchesFailingIs503NotAnEmptyList`; FE `page.test.tsx:213`. |
| AC-5.3 (429) | Pass | `SemanticQueryLimiter` Semaphore(8) áp mọi chế độ, `release` trong `finally` (`SemanticSearchService.java:138-142`); FE banner vàng + "Thử lại" (`page.tsx:218-224`); `SemanticQueryLimiterTest`, FE `page.test.tsx:227`. |
| AC-5.4 (banner suy giảm + Thử lại) | Pass | Copy 2 chế độ nguyên văn (`page.tsx:38-41`); nút disabled khi `loading` (`page.tsx:195`); FE `page.test.tsx:247-251,266`. |
| AC-6 / 6.1 / 6.2 (≤2 trang) | Pass | `page ∈ {0,1}` ⇒ 400 (`SemanticSearchService.java:112`); `MAX_PAGES=2`, nút "Trang sau" chỉ ở trang 1, gợi ý thu hẹp khi `hasMore`, tổng "Khoảng N … (số liệu gần đúng)" (`page.tsx`); IT `pagesAreSlicesOfTheSameRankingAndAtMostTwoExist`; FE `page.test.tsx:289`. |
| AC-7 / 7.1 / 7.2 (audit) | Pass | `SemanticSearchService.recordAudit` (`:257-294`) action `search.executed`, async, query cắt 200, `mode`=hybrid, `degradedMode`, `minScore`, `resultCount`, không excerpt; ghi cả 403/429/503; `AuditService.ALLOWED_METADATA_KEYS` thêm `mode/degradedMode/minScore`; IT `everyQueryIsAuditedWithoutExcerptsAndA403IsAuditedToo`, `SemanticSearchServiceTest` (`a503IsAudited`). |
| AC-8 / 8.1 (ghi chú chờ kiểm duyệt) | Pass | `documentDigitizationStatus` trong DTO; `SemanticResultCard.tsx` ghi chú xám khi `!= 'confirmed'`, nguyên văn; IT `theDocumentsDigitizationStatusIsReturned...`; FE `page.test.tsx:134`. |

## Kiểm tra rò rỉ quyền (trọng tâm)

- Filter phạm vi nằm **trong mệnh đề `knn`** (`KnnSegmentSearcher.java:76`, `.filter(filter)`); không có `post_filter` hay `bool` bên ngoài. Bộ filter = `SearchQueryBuilder.buildFilterClauses(...)` (bắt đầu bằng `ScopeFilterBuilder.build`) — **cùng một hàm** nhánh lexical dùng (`SearchQueryBuilder.java:108,161`). Thiếu `ScopeContext` ⇒ `IllegalArgumentException` (`KnnSegmentSearcher.java:61-63`), không có đường dựng knn không scope. `KnnSegmentSearcherTest` kiểm tra cấu trúc.
- Double-check Postgres: `SemanticResultHydrator.hydrate` truy vấn `Document` bằng `BusinessDataScope.forDocuments(...)` AND `id IN candidates`; document không qua ⇒ bị bỏ (`:115-118`). Có IT mô phỏng ACL chỉ mục lệch (`whenTheIndexAclHasDrifted...`) và thu hồi share ở Postgres khi chỉ mục cũ (`aGroupShareMakes...`).
- Không lộ `sed`: loại ở filter knn, ở hydrator, và IT có vector `sed` khớp hoàn hảo vẫn không xuất hiện. (Nhánh lexical không có `must_not sed` trong truy vấn nhưng hydrator loại; xem đề xuất.)
- Không cache kết quả; chỉ cache vector câu hỏi (key không chứa user) — `EmbeddingClient`, IT `anIdenticalQuestionIsServedFromTheVectorCache...ResultsAreNeverCached`.
- Client không gửi được `mode`/`minScore` (bị bỏ qua — IT `clientSuppliedModeOrMinScoreFieldsAreIgnored`); `PERM_document.read` (`SemanticSearchController.java:34`), IT `roleWithoutDocumentReadCannotCallTheEndpoint`.

## Đánh giá các điểm lệch spec do DEV tự báo

| Điểm lệch | Đánh giá |
|---|---|
| Mapping faiss + `innerproduct` + SQ fp16 thay cho `on_disk` | **Chấp nhận.** Có lý do kỹ thuật (OpenSearch 2.17.1 trả 500 với `on_disk` khi shard có document không vector; faiss HNSW không hỗ trợ `cosinesimil`), đã ghi runbook, `KnnScore.toCosine` có unit test (`KnnScoreTest`). Rủi ro: ip == cosine chỉ đúng nếu endpoint trả vector đã chuẩn hoá L2 (xem đề xuất N1); đổi `on_disk` ⇒ tăng RAM index (R7 trong spec) — cần theo dõi dung lượng. |
| Segment id là `String` (id tổng hợp `<docId>:title/note`) | **Chấp nhận.** Cần thiết vì title/note không có hàng `content_segment`; hydrator xử lý rõ 3 dạng id (`SemanticResultHydrator.java:143-172`); `LexicalHit` đổi sang String đồng bộ. Không ảnh hưởng `/search` (toàn bộ test search vẫn xanh). |
| `semantic.min_score` kiểu STRING | **Chấp nhận.** `system_config` không có kiểu số thập phân; validator ép 0–1 (`SystemConfigValidator` `requireUnitInterval`), service fallback 0,50 khi giá trị hỏng — fail-safe theo hướng không nới ngưỡng. |
| `SemanticResultCard` riêng thay vì tái dùng `SearchResultCard` | **Chấp nhận.** Hình dạng dữ liệu khác (1 dòng/đoạn, không highlight, có `summary`); copy badge/ghi chú giữ nguyên văn và FE test xác nhận; không sửa `SearchResultCard` nên `/search` không đổi. Nên bổ sung unit test riêng cho thẻ (N5). |
| Không giới hạn số từ query (chỉ ≤256 ký tự) | **Chấp nhận, đúng spec** (§3: chỉ rỗng/>256 là 400; khác `/search` giới hạn 32 token). Có IT. |
| `SearchIndexBootstrap` không gắn alias khi đã có index `segment` khác | **Chấp nhận có điều kiện.** Tránh alias đa-index làm hỏng ghi; có log WARN. Hệ quả: môi trường đang có `segment-v1` sẽ tạo `segment-v2` rời, k-NN trên alias cũ lỗi ⇒ endpoint chạy `lexical_only` cho tới khi reindex+swap (vẫn an toàn, không rò rỉ). Cần runbook được thực hiện trước khi bật cờ (N3). |

## Kết quả test/lint đã chạy

```
cd backend && mvn -o verify
  BUILD SUCCESS. Unit: Tests run: 732, Failures 0, Errors 0, Skipped 0.
  IT (failsafe): Tests run: 520, Failures 0, Errors 0, Skipped 0.
  Bao gồm: SemanticSearchControllerIT (21), SemanticIndexingIT (10), SemanticSearchServiceTest, HybridFusionTest,
  KnnSegmentSearcherTest, KnnScoreTest, EmbeddingClientTest, SemanticResultHydratorTest, SemanticQueryLimiterTest,
  EmbedRequestConsumerTest, SemanticIndexReconciliationJobTest, SearchIndexOutboxPollerEmbedTest.
  (Có dòng "Surefire is going to kill self fork JVM" sau System.exit(0) — nhiễu của fork, không ảnh hưởng kết quả.)
cd web && npx tsc --noEmit        -> exit 0
cd web && npm run lint            -> exit 0 (0 error, 1 warning: web/coverage/block-navigation.js - file sinh ra, không phải mã nguồn)
cd web && npx vitest run          -> Test Files 48 passed; Tests 447 passed
grep Qdrant/TEI/bge-m3 trong backend/src, pom.xml, docker-compose*.yml -> chỉ còn 1 comment lịch sử ở migration V26 (không sửa migration).
```

## Vấn đề chặn (Blocking)

Không có.

## Đề xuất cải thiện (Non-blocking)

- [ ] N1: `EmbeddingClient` chỉ validate số chiều, không kiểm tra chuẩn L2 (~1,0). Vì index dùng `innerproduct`, nếu endpoint đổi/triển khai khác không chuẩn hoá thì "cosine" và `min_score` lệch âm thầm (rủi ro chất lượng, không rò rỉ). Đề xuất kiểm tra |norm−1|<ε hoặc tự chuẩn hoá phía backend (cả câu hỏi lẫn tài liệu).
- [ ] N2: Nhánh lexical không có `must_not content_source=sed` nên hit `sed` chiếm chỗ trong top-k lexical/RRF rồi mới bị hydrator loại (không rò rỉ, chỉ làm hao trang kết quả). Cân nhắc truyền cờ loại `sed` cho `lexicalSearch` (lưu ý hợp đồng chữ ký không đổi).
- [ ] N3: Điều kiện go-live/vận hành chưa xong ở bước này: (a) hiệu chỉnh `semantic.min_score`/`top_k`/instruction bằng tập vàng tiếng Việt (T23 mới có công cụ `backend/scripts/semantic-calibrate.py`); (b) thực hiện reindex `segment-v1 → segment-v2` + swap alias trên môi trường đã có dữ liệu trước khi bật `app.semantic.enabled`; (c) xác nhận giới hạn batch/p95 của endpoint embedding với nhóm AI (Q-7); (d) đo dung lượng/RAM index sau khi bỏ `on_disk` (R7).
- [ ] N4: Với kết quả `title`/`note` (vị trí "không có vị trí cụ thể") không phải video, thẻ chỉ hiện nhãn "Không có vị trí cụ thể" mà không có liên kết mở tài liệu (nút "Mở tài liệu từ đầu" chỉ cho `summary`). Cân nhắc cho mở tài liệu ở trang 1 để người dùng tra cứu tiếp (AC-2 "bấm dẫn nguồn để nhảy tới").
- [ ] N5: Bổ sung unit test riêng cho `SemanticResultCard` (hiện chỉ được phủ gián tiếp qua `page.test.tsx`); tương tự test FE cho từng banner suy giảm đã có nhưng nên assert nguyên văn cả hai chuỗi.
- [ ] N6: Spec §7 yêu cầu metric Micrometer (`semantic_search_latency_ms`, `semantic_branch_failures_total`, …); code hiện chỉ có log INFO. Chưa thấy metric/alert — nên bổ sung trước go-live để giám sát tỷ lệ rỗng/suy giảm.
- [ ] N7: `branchExecutor = Executors.newCachedThreadPool()` không giới hạn; an toàn nhờ semaphore 8 nhưng nên đặt tên thread/giới hạn tường minh. `future.cancel(true)` với `CompletableFuture` không ngắt tác vụ đang chạy — các lời gọi HTTP phải tự có timeout client.
- [ ] N8: DoD spec §11 chưa được tick trong file spec (checkbox còn trống); DEV cập nhật sau khi report này được chấp nhận. Một số mục checklist bảo mật §8 trong spec còn `[ ]` dù đã được thoả bởi code/test — nên tick lại.

## Kiểm tra thêm

- [x] Security checklist trong spec đã thoả (fail-closed 403, filter trong knn, double-check Postgres, không cache kết quả, audit không excerpt, ≤8 truy vấn đồng thời). Không log nội dung query (chỉ độ dài, `SemanticSearchService.java:196`).
- [ ] Observability: có log INFO theo spec (userSub, queryLen, degradedMode, resultCount, thời gian tổng) nhưng **chưa có độ trễ từng nhánh và metric** (N6).
- [x] Migration/rollback: không có migration Postgres mới; `segment-v2` tạo song song, alias không bị gắn đôi; cờ `app.semantic.enabled` che endpoint (404) — rollback an toàn.
