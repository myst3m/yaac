# Yaac リファクタリングTODO

## 1. core.cljの巨大化解決 🔥 **高優先度**

**現状**: 1459行、96個の関数が1つのファイルに集中  
**問題**: 保守性低下、テスト困難、責任範囲不明確

### 分割提案
```
src/yaac/
├── api/
│   ├── client.clj      # HTTP通信、認証ヘッダー
│   ├── organizations.clj # 組織管理API
│   ├── environments.clj  # 環境管理API
│   ├── applications.clj  # アプリケーション管理API
│   └── assets.clj       # Exchange資産管理API
├── cache/
│   └── core.clj        # 改善されたキャッシュ実装
└── core.clj            # エントリーポイントのみ
```

## 2. HTTPリクエストパターンの統一 🔥 **高優先度**

**現状**: 28箇所で重複するHTTPリクエストパターン

### 実装案
```clojure
(defn api-request [method path & [opts]]
  (-> (http/request method (gen-url path) 
                    (merge {:headers (default-headers)} opts))
      (parse-response)))

;; 使用例
(defn get-organizations []
  (api-request :get "/accounts/api/organizations"))
```

## 3. org-id/env-id変換の重複解決 ⚡ **中優先度**

**現状**: 18箇所で `(let [org-id (org->id ...)]` パターン

### 実装案
```clojure
(defn with-org-env-ids [f]
  (fn [org env & args]
    (let [org-id (org->id org)
          env-id (env->id env)]
      (apply f org-id env-id args))))

;; 使用例
(def get-applications 
  (with-org-env-ids 
    (fn [org-id env-id & opts]
      (api-request :get (format "/path/%s/%s/apps" org-id env-id)))))
```

## 4. エラーハンドリングの統一 ⚡ **中優先度**

**現状**: 
- `try-wrap`マクロが不完全（catch節が空）
- 各モジュールで異なるエラー処理

### 実装案
```clojure
(defmacro with-error-handling [& body]
  `(try
     ~@body
     (catch Exception e#
       (if (instance? clojure.lang.ExceptionInfo e#)
         (throw e#)
         (throw (ex-info "Unexpected error" 
                        {:cause (.getMessage e#)} e#))))))
```

## 5. キャッシュ実装の改善 🚀 **中優先度**

**現状**: 毎回ファイルI/O、TTLなし、エラーハンドリング不十分

### 改善案
- メモリ＋ファイル階層キャッシュ
- TTL（有効期限）対応
- 非同期永続化
- 個別ファイル保存（競合回避）

詳細実装は別途 `src/yaac/cache/core.clj` に実装予定

## 6. 循環依存の解決 🔧 **低優先度**

**現状**: core.cljが自分自身を要求 (`[yaac.core :as yc]`)

### 対策
依存関係の整理と、共通ユーティリティの分離

## 7. 並列処理マクロの改善 🔧 **低優先度**

**現状**: `on-threads`マクロが複雑で可読性低い

### 改善案
- core.asyncの活用を明確化
- エラーハンドリングの改善
- ドキュメント追加

## 8. テストカバレッジの向上 📋 **低優先度**

**現状**: test/yaacに2ファイルのみ

### TODO
- [ ] 各モジュールに対応するテスト追加
- [ ] APIクライアントのモックテスト
- [ ] キャッシュ機能のテスト
- [ ] エラーハンドリングのテスト

## 実装優先順位

### Phase 1: 基盤整備
1. HTTPクライアントの統一実装
2. エラーハンドリングの統一
3. キャッシュシステムの改善

### Phase 2: 構造改善  
1. core.cljの分割
2. 循環依存の解決
3. org-id/env-id変換の統一

### Phase 3: 品質向上
1. テストカバレッジ向上
2. 並列処理の改善
3. ドキュメント整備

## 備考

- リファクタリング時は既存APIの互換性を維持
- GraalVMネイティブコンパイルへの影響を考慮
- パフォーマンステストで退行がないことを確認