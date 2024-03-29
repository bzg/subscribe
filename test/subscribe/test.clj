;; Copyright (c) 2019-2023 Bastien Guerry <bzg@bzg.fr>
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSES/EPL-2.0.txt

(ns subscribe.test
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.java.io :as io]
            [core :refer :all]
            [config :as config]
            [i18n :as i18n])
  (:import org.apache.commons.validator.routines.UrlValidator))

(defn valid-url? [url-str]
  (let [validator (UrlValidator.)]
    (.isValid validator url-str)))

(defn regexp? [re-str]
  (= (re-pattern re-str) re-str))

(deftest application-environment-variables
  (testing "Checking if all environment variables are set."
    (is (and (string? (System/getenv "SUBSCRIBE_SMTP_LOGIN"))
             (string? (System/getenv "SUBSCRIBE_SMTP_PASSWORD"))
             (string? (System/getenv "SUBSCRIBE_SMTP_HOST"))
             (string? (System/getenv "SUBSCRIBE_SMTP_PORT"))
             (string? (System/getenv "SUBSCRIBE_PORT"))
             (string? (System/getenv "SUBSCRIBE_BASEURL"))))))

(deftest backends-environment-variables
  (testing "Checking if backends environment variables are set."
    (when ((:backends config/config) "mailgun")
      (is (string? (System/getenv "MAILGUN_API_KEY"))))
    (when ((:backends config/config) "sendinblue")
      (is (string? (System/getenv "SENDINBLUE_API_KEY"))))
    (when ((:backends config/config) "mailjet")
      (is (and (string? (System/getenv "MAILJET_API_KEY"))
               (string? (System/getenv "MAILJET_API_SECRET")))))))

(deftest lists-exists
  (testing "Checking connection and existing list(s)."
    (get-lists-from-server!)
    (is (not-empty @core/lists))))

;; Mandatory configuration keys
(s/def ::admin-email string?)
(s/def ::backends (s/coll-of (into #{} (map :backend config/backends))))

;; Optional configuration keys
(s/def ::base-url valid-url?)
(s/def ::from string?)
(s/def ::to string?)
(s/def ::msg-id string?)
(s/def ::return-url valid-url?)
(s/def ::tos-url valid-url?)
(s/def ::port int?)
(s/def ::smtp-host string?)
(s/def ::smtp-login string?)
(s/def ::smtp-password string?)
(s/def ::locale i18n/supported-languages)
(s/def ::team string?)
(s/def ::log-file string?)
(s/def ::lists-exclude-regexp regexp?)
(s/def ::lists-include-regexp regexp?)
(s/def ::warn-every-x-subscribers int?)

(s/def ::config
  (s/keys
   :req-un [::admin-email ::backends]
   :opt-un [::base-url ::return-url ::tos-url ::port
            ::from ::msg-id ::locale ::team ::log-file
            ::lists-exclude-regexp
            ::lists-include-regexp
            ::smtp-login ::smtp-password ::smtp-host
            ::warn-every-x-subscribers]))

;; FIXME: Also test per-list configuration
(deftest configuration-map
  (testing "Checking entries in the configuration map."
    (is (s/valid? ::config config/config))))
