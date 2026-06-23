(ns analitica.web.cors-test
  (:require [clojure.test :refer [deftest is]]
            [analitica.web.server :as server]
            [analitica.config :as config]))

(deftest cors-patterns-anchor-exact-origins
  (with-redefs [config/cors-origins (constantly ["https://marker.shegida.ru" "http://localhost:3000"])]
    (let [pats (#'server/cors-origin-patterns)]
      (is (some #(re-matches % "https://marker.shegida.ru") pats))
      (is (some #(re-matches % "http://localhost:3000") pats))
      (is (not-any? #(re-matches % "https://evil.test") pats))
      ;; must NOT be a wildcard
      (is (not-any? #(re-matches % "https://marker.shegida.ru.evil.test") pats))
      ;; exactly one pattern per configured origin — no stray catch-all can sneak in
      (is (= 2 (count pats))))))

;; End-to-end through the wired handler: proves the live wildcard was actually
;; removed (a helper-only test passes even if server.clj:1368 still has [#".*"]).
(deftest wired-cors-blocks-disallowed-origin
  (with-redefs [config/cors-origins (constantly ["https://marker.shegida.ru"])]
    (let [h    (server/app)
          ;; "/" exists in Plan A (302 redirect); CORS headers are applied regardless of status
          good (h {:request-method :get :uri "/"
                   :headers {"origin" "https://marker.shegida.ru"}})
          evil (h {:request-method :get :uri "/"
                   :headers {"origin" "https://evil.test"}})]
      ;; allowed origin is echoed; disallowed origin gets no ACAO grant
      (is (= "https://marker.shegida.ru" (get-in good [:headers "Access-Control-Allow-Origin"])))
      (is (nil? (get-in evil [:headers "Access-Control-Allow-Origin"]))))))
