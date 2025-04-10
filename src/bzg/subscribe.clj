#!/usr/bin/env bb

;; Copyright (c) Bastien Guerry
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: EPL-2.0.txt

;; This script runs a web app to let users subscribe to a Mailgun
;; mailing list with double opt-in. You need a Mailgun API key.
;;
;; You need to set these environment variables:
;;
;; export MAILGUN_API_KEY="xxxxxxxxxxxxxxxx-xxxxxxxx-xxxxxxxx"
;; export MAILGUN_API_ENDPOINT="https://api.eu.mailgun.net/v3"
;; export MAILGUN_LIST_ID="my@list.com"
;; export MAILGUN_LIST_NAME="My Newsletter"
;;
;; For double opt-in email sending:
;;
;; export SUBSCRIBE_SMTP_HOST="smtp.example.com"
;; export SUBSCRIBE_SMTP_PORT="587"
;; export SUBSCRIBE_SMTP_USER="user@example.com"
;; export SUBSCRIBE_SMTP_PASS="yourpassword"
;; export SUBSCRIBE_SMTP_FROM="newsletter@example.com"
;; export SUBSCRIBE_BASE_URL="http://localhost"
;;
;; The application runs on http://localhost:8080. You can try:
;;
;; ~$ subscribe
;;
;; You can also set a base path to deploy the application on a
;; subdirectory (e.g. "http://localhost:8080/newsletter"):
;;
;; export SUBSCRIBE_BASE_PATH="/newsletter"
;;
;; You can use a EDN configuration file for setting options:
;;
;; ~$ subscribe --config config.edn
;;
;; This config file can let you set/override these variables:
;;
;; - ui-strings
;; - mailgun-api-endpoint
;; - mailgun-api-key
;; - mailgun-list-id
;; - mailgun-list-name
;; - base-url
;; - base-path
;; - subscribe-smtp-host
;; - subscribe-smtp-port
;; - subscribe-smtp-user
;; - subscribe-smtp-pass
;; - subscribe-smtp-from
;; - index-tpl
;;
;; ~$ subscribe -h # Show more information

(ns bzg.subscribe
  (:require [babashka.cli :as cli]
            [babashka.deps :as deps]
            [babashka.http-client :as http]
            [babashka.pods :as pods]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [org.httpkit.server :as server]
            [selmer.parser :as selmer]
            [taoensso.timbre :as log]))

(deps/add-deps '{:deps {org.clojars.askonomm/ruuter {:mvn/version "1.3.4"}}})
(pods/load-pod 'tzzh/mail "0.0.3")
(require '[ruuter.core :as ruuter]
         '[pod.tzzh.mail :as mail])

(def cli-options
  (into
   (sorted-map)
   {:help      {:alias :h :desc "Display help"}
    :port      {:alias :p :desc "Port number" :default 8080 :coerce :int}
    :base-path {:alias :b :desc "Base path" :coerce :string}
    :base-url  {:alias :u :desc "Base URL for confirmation links (no port)" :coerce :string}
    :log-level {:alias :l :desc "Log level (debug, info, warn, error)" :default "info" :coerce :string}
    :config    {:alias :c :desc "Config file path" :coerce :string}
    :log-file  {:alias :L :desc "Log file" :coerce :string}
    :css-file  {:alias :S :desc "CSS file path" :coerce :string}
    :index-tpl {:alias :I :desc "Index HTML template file path" :coerce :string}}))

(defn print-usage []
  (println "Usage: subscribe [options]")
  (println "\nOptions:")
  (println (cli/format-opts {:spec cli-options}))
  (println "\nEnvironment variables:")
  (println "  MAILGUN_LIST_ID             Mailgun list identifier")
  (println "  MAILGUN_API_ENDPOINT        Mailgun API endpoint")
  (println "  MAILGUN_API_KEY             Mailgun API key")
  (println "  SUBSCRIBE_BASE_PATH         Base path for deployments in subdirectories")
  (println "  SUBSCRIBE_SMTP_HOST         SMTP server hostname")
  (println "  SUBSCRIBE_SMTP_PORT         SMTP server port")
  (println "  SUBSCRIBE_SMTP_USER         SMTP username")
  (println "  SUBSCRIBE_SMTP_PASS         SMTP password")
  (println "  SUBSCRIBE_SMTP_FROM         From email address for confirmation emails")
  (println "  SUBSCRIBE_BASE_URL          Base URL for confirmation links")
  (println "\nExamples:")
  (println "  subscribe                   # Run on default port 8080")
  (println "  subscribe -p 8888           # Run on port 4444")
  (println "  subscribe -l debug          # Specify log level as \"debug\"")
  (println "  subscribe -L log.txt        # Specify a log file name")
  (println "  subscribe -c config.edn     # Load configuration from file")
  (println "  subscribe -b /app           # Set base path to /app")
  (println "  subscribe -u https://z.org  # Set confirmation URL")
  (println "  subscribe -S style.css      # Load CSS from file")
  (println "  subscribe -I index.html     # Load index template from file")
  (System/exit 0))

;; Setting defaults
(def rate-limit-window (* 60 60 1000)) ;; 1 hour in milliseconds
(def max-requests-per-window 10) ;; Maximum 10 requests per IP per hour
(def ip-request-log (atom {}))
(def last-pruned-time (atom (System/currentTimeMillis)))
(def token-store (atom {}))

;; Data validation
(s/def ::email
  (s/and string?
         #(<= (count %) 254)
         #(re-matches #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$" %)
         #(not (re-find #"\.{2,}|@{2,}|\_{2,}|\-{2,}" %))))

(s/def ::ui-strings map?)
(s/def ::mailgun-list-name string?)
(s/def ::mailgun-list-id (s/and string? #(not (re-find #"\s" %))))
(s/def ::mailgun-api-endpoint (s/and string? #(not (re-find #"\s" %))))
(s/def ::base-path string?)
(s/def ::css-file string?)
(s/def ::index-tpl string?)
(s/def ::smtp-config
  (s/keys :req [::subscribe-smtp-host ::subscribe-smtp-port
                ::subscribe-smtp-user ::subscribe-smtp-pass
                ::subscribe-smtp-from]))

(s/def ::config
  (s/keys :opt-un [::ui-strings
                   ::mailgun-list-id
                   ::mailgun-list-name
                   ::mailgun-api-endpoint
                   ::base-path
                   ::base-url
                   ::css-file
                   ::index-tpl]
          :opt [::smtp-config]))

(s/def ::form-email ::email)
(s/def ::form-action #{"subscribe" "unsubscribe"})
(s/def ::csrf_token string?)
(s/def ::website (s/nilable string?))
(s/def ::subscription-form
  (s/keys :req-un [::email ::csrf_token]
          :opt-un [::form-action ::website]))

(defn validate-config [config-data]
  (or (s/valid? ::config config-data)
      (log/error "Invalid configuration:" (s/explain-str ::config config-data))))

(def token-expirations
  {:csrf        (* 8 60 60 1000) ;; 8 hours for CSRF tokens
   :subscribe   (* 24 60 60 1000) ;; 24 hours for subscription confirmations
   :unsubscribe (* 24 60 60 1000)}) ;; 24 hours for unsubscription confirmations

(defn generate-token-key []
  (let [random-bytes (byte-array 32)]
    (.nextBytes (java.security.SecureRandom.) random-bytes)
    (.encodeToString (java.util.Base64/getUrlEncoder) random-bytes)))

(defn create-token [token-type data]
  (let [token-key  (generate-token-key)
        now        (System/currentTimeMillis)
        expiration (get token-expirations token-type (* 24 60 60 1000))
        token-data {:type       token-type
                    :data       data
                    :created-at now
                    :expires-at (+ now expiration)}]
    (swap! token-store assoc token-key token-data)
    (log/debug "Created token:" token-key "of type" token-type)
    token-key))

(defn validate-token [token-key & {:keys [token-type consume] :or {consume false}}]
  (when (string? token-key)
    (let [token-data (get @token-store token-key)
          now        (System/currentTimeMillis)]
      (when (and token-data
                 (< now (:expires-at token-data))
                 (or (nil? token-type) (= token-type (:type token-data))))
        (when consume (swap! token-store dissoc token-key))
        token-data))))

(defn has-pending-confirmation? [email token-type]
  (let [now (System/currentTimeMillis)]
    (some (fn [[_ token-data]]
            (and (= (:type token-data) token-type)
                 (= (get-in token-data [:data :email]) email)
                 (< now (:expires-at token-data))))
          @token-store)))

(defn get-or-create-csrf-token [ip]
  (let [existing-tokens (filter (fn [[_ data]]
                                  (and (= (:type data) :csrf)
                                       (= (get-in data [:data :ip]) ip)))
                                @token-store)]
    (if (seq existing-tokens)
      (first (keys existing-tokens))
      (create-token :csrf {:ip ip}))))

(defn validate-csrf-token [token-key ip]
  (when-let [token-data (validate-token token-key :token-type :csrf)]
    (= (get-in token-data [:data :ip]) ip)))

(defn normalize-path [path]
  (if (str/blank? path)
    ""
    (-> path str/trim
        (as-> p (if (str/starts-with? p "/") p (str "/" p)))
        (str/replace #"/$" ""))))

(defn normalize-url [url] (str/replace url #"/$" "" ))

(defn join-paths
  "Join multiple path segments together with proper handling of slashes."
  [& segments]
  (or (->> segments (remove str/blank?) (map normalize-path) (apply str) not-empty) "/"))

(defn join-url [base-url & path-segments]
  (when (not-empty base-url)
    (let [normalized-base (normalize-url base-url)
          path-part       (apply join-paths path-segments)]
      (str normalized-base path-part))))

(defn generate-default-base-url [port]
  (let [port-str     (str port)
        default-base (or (System/getenv "SUBSCRIBE_BASE_URL")
                         (str "http://localhost:" port-str))]
    (str (normalize-url default-base) "/")))

(def ui-strings-data
  {:en
   {:page
    {:title      "Mailing list subscription"
     :heading    "Subscribe to our mailing list"
     :subheading "Join our mailing list to receive updates and news"
     :footer     "Made with <a target=\"new\" href=\"https://github.com/bzg/subscribe\">subscribe</a>"}
    :form
    {:email-placeholder  "you@example.com"
     :website-label      "Website (leave this empty)"
     :subscribe-button   "Subscribe"
     :unsubscribe-button "Unsubscribe"}
    :messages
    {:back-to-homepage                         "Back"
     :already-subscribed                       "Already subscribed"
     :already-subscribed-message               "The email <code>%s</code> is already subscribed."
     :not-subscribed                           "Warning: not subscribed"
     :not-subscribed-message                   "The email <code>%s</code> is not currently subscribed. No action was taken."
     :confirmation-pending                     "Confirmation pending"
     :confirmation-pending-message             "<p>A confirmation email has already been sent to <code>%s</code>.</p><p>Please check your inbox or spam folder for the confirmation link.<p>"
     :operation-failed                         "Operation failed"
     :operation-failed-message                 "The operation was unsuccessful, please try again."
     :rate-limit                               "Rate limit exceeded"
     :rate-limit-message                       "Too many subscription attempts from your IP address. Please try again later."
     :invalid-email                            "Invalid email format"
     :invalid-email-message                    "<p>The email <code>%s</code> appears to be invalid.</p><p>Please check the format and try again.<p>"
     :spam-detected                            "Submission rejected"
     :spam-detected-message                    "Your submission has been identified as potential spam and has been rejected."
     :csrf-invalid                             "Security validation failed"
     :csrf-invalid-message                     "Security token validation failed. This could happen if you used an old form or if your session expired."
     :confirmation-sent                        "Confirmation email sent"
     :confirmation-sent-message                "<p>A confirmation email has been sent to <code>%s</code>.</p><p>Please check your inbox and click the confirmation link.<p>"
     :subscribe-confirmation-success           "Thank you!"
     :subscribe-confirmation-success-message   "Your subscription has been confirmed. Thank you!"
     :unsubscribe-confirmation-success         "Bye!"
     :unsubscribe-confirmation-success-message "Your unsubscription has been confirmed."
     :confirmation-error                       "Confirmation error"
     :confirmation-error-message               "The confirmation link is invalid or has expired. Please try subscribing again."
     :confirmation-email-failed                "Confirmation email could not be sent"
     :confirmation-email-failed-message        "<p>We couldn't send a confirmation email to <code>%s</code>.</p><p>Please try again later.<p>"}
    :emails
    {:subscription-confirm-subject   "[%s] Please confirm your subscription"
     :subscription-confirm-body-text "Thank you for subscribing to our mailing list with your email address: %s.\n\nPlease confirm your subscription by clicking on this link:\n\n%s\n\nIf you did not request this subscription, you can ignore this email."
     :subscription-confirm-body-html "<html><body><p>Thank you for subscribing to our mailing list with your email address: <code>%s</code>.</p><p>Please confirm your subscription by clicking on the following link:</p><p><a href=\"%s\">Confirm your subscription</a></p><p>If you did not request this subscription, you can ignore this email.</p></body></html>"
     :unsubscribe-confirm-subject    "[%s] Please confirm your unsubscription"
     :unsubscribe-confirm-body-text  "You have requested to unsubscribe from our mailing list with the email address: %s.\n\nPlease confirm your unsubscription by clicking on the following link:\n\n%s\n\nIf you did not request this unsubscription, you can ignore this email."
     :unsubscribe-confirm-body-html  "<html><body><p>You have requested to unsubscribe from our mailing list with the email address: <code>%s</code>.</p><p>Please confirm your unsubscription by clicking on the following link:</p><p><a href=\"%s\">Confirm your unsubscription</a></p><p>If you did not request this unsubscription, you can ignore this email.</p></body></html>"}}
   :fr
   {:page
    {:title      "Abonnement par e-mail"
     :heading    "Abonnement à notre liste de diffusion"
     :subheading "Rejoignez notre liste pour recevoir des nouvelles"
     :footer     "Fait avec <a target=\"new\" href=\"https://github.com/bzg/subscribe\">subscribe</a>"}
    :form
    {:email-placeholder  "vous@exemple.com"
     :website-label      "Site web (laissez ce champ vide)"
     :subscribe-button   "Abonnement"
     :unsubscribe-button "Désabonnement"}
    :messages
    {:back-to-homepage                         "Accueil"
     :already-subscribed                       "Déjà abonné"
     :already-subscribed-message               "L'adresse e-mail <code>%s</code> est déjà abonnée."
     :not-subscribed                           "Attention : non abonné"
     :not-subscribed-message                   "<p>L'adresse e-mail <code>%s</code> n'est pas actuellement abonnée.</p><p>Aucune action n'a été effectuée.</p>"
     :confirmation-pending                     "Confirmation en attente"
     :confirmation-pending-message             "<p>Un email de confirmation a déjà été envoyé à <code>%s</code>.</p><p>Veuillez vérifier votre boîte de réception ou dossier spam pour le lien de confirmation.<p>"
     :operation-failed                         "Échec de l'opération"
     :operation-failed-message                 "<p>L'opération n'a pas pu aboutir avec cette erreur:</p><p><code>%s</code>."
     :rate-limit                               "Limite de taux dépassée"
     :rate-limit-message                       "Trop de tentatives d'abonnement depuis votre adresse IP. Veuillez réessayer plus tard."
     :invalid-email                            "Format d'e-mail invalide"
     :invalid-email-message                    "<p>L'adresse e-mail <code>%s</code> semble être invalide.<p/><p>Veuillez vérifier le format et réessayer.</p>"
     :spam-detected                            "Soumission rejetée"
     :spam-detected-message                    "Votre soumission a été identifiée comme spam potentiel et a été rejetée."
     :csrf-invalid                             "Échec de validation de sécurité"
     :csrf-invalid-message                     "La validation du jeton de sécurité a échoué. Cela peut se produire si vous avez utilisé un ancien formulaire ou si votre session a expiré."
     :confirmation-sent                        "Email de confirmation envoyé"
     :confirmation-sent-message                "<p>Un email de confirmation a été envoyé à <code>%s</code>.</p><p>Veuillez vérifier votre boîte de réception et cliquer sur le lien de confirmation.</p>"
     :subscribe-confirmation-success           "Merci !"
     :subscribe-confirmation-success-message   "Votre abonnement a été confirmé."
     :unsubscribe-confirmation-success         "Au revoir !"
     :unsubscribe-confirmation-success-message "Votre désabonnement est confirmé."
     :confirmation-error                       "Erreur de confirmation"
     :confirmation-error-message               "<p>Le lien de confirmation n'est pas valide ou a expiré.<p/><p>Veuillez essayer de vous abonner à nouveau.</p>"
     :confirmation-email-failed                "L'email de confirmation n'a pas pu être envoyé."
     :confirmation-email-failed-message        "<p>Nous n'avons pas pu envoyer un email de confirmation à <code>%s</code>.</p><p>Veuillez réessayer plus tard.</p>"}
    :emails
    {:subscription-confirm-subject   "[%s] Veuillez confirmer votre abonnement"
     :subscription-confirm-body-text "Merci de vous être abonné à notre liste de diffusion avec votre adresse e-mail : %s.\n\nVeuillez confirmer votre abonnement en cliquant sur ce lien :\n\n%s\n\nSi vous n'avez pas demandé cet abonnement, vous pouvez ignorer cet e-mail."
     :subscription-confirm-body-html "<html><body><p>Merci de vous être abonné à notre liste de diffusion avec votre adresse e-mail : <code>%s</code>.</p><p>Veuillez confirmer votre abonnement en cliquant sur ce lien :</p><p><a href=\"%s\">Confirmer votre abonnement</a></p><p>Si vous n'avez pas demandé cet abonnement, vous pouvez ignorer cet e-mail.</p></body></html>"
     :unsubscribe-confirm-subject    "[%s] Veuillez confirmer votre désabonnement"
     :unsubscribe-confirm-body-text  "Vous avez demandé à vous désabonner de notre liste de diffusion avec l'adresse e-mail : %s.\n\nVeuillez confirmer votre désabonnement en cliquant sur ce lien :\n\n%s\n\nSi vous n'avez pas demandé ce désabonnement, vous pouvez ignorer cet e-mail."
     :unsubscribe-confirm-body-html  "<html><body><p>Vous avez demandé à vous désabonner de notre liste de diffusion avec l'adresse e-mail : <code>%s</code>.</p><p>Veuillez confirmer votre désabonnement en cliquant sur ce lien :</p><p><a href=\"%s\">Confirmer votre désabonnement</a></p><p>Si vous n'avez pas demandé ce désabonnement, vous pouvez ignorer cet e-mail.</p></body></html>"}}})

(def app-config
  (atom {:mailgun-list-id      (System/getenv "MAILGUN_LIST_ID")
         :mailgun-list-name    (System/getenv "MAILGUN_LIST_NAME")
         :mailgun-api-endpoint (or (System/getenv "MAILGUN_API_ENDPOINT")
                                   "https://api.mailgun.net/v3")
         :mailgun-api-key      (System/getenv "MAILGUN_API_KEY")
         :base-path            (normalize-path (or (System/getenv "SUBSCRIBE_BASE_PATH") ""))
         :base-url             (generate-default-base-url 8080)
         :subscribe-smtp-host  (System/getenv "SUBSCRIBE_SMTP_HOST")
         :subscribe-smtp-port  (System/getenv "SUBSCRIBE_SMTP_PORT")
         :subscribe-smtp-user  (System/getenv "SUBSCRIBE_SMTP_USER")
         :subscribe-smtp-pass  (System/getenv "SUBSCRIBE_SMTP_PASS")
         :subscribe-smtp-from  (System/getenv "SUBSCRIBE_SMTP_FROM")
         :ui-strings           ui-strings-data}))

(defn config [& ks] (get-in @app-config ks))

(defn get-ui-strings [& [lang]]
  (get (config :ui-strings) (or lang :en)))

(defn with-base-path [& path-segments]
  (let [base-path       (config :base-path)
        normalized-base (normalize-path base-path)
        path-part       (apply join-paths path-segments)]
    (cond
      (str/blank? normalized-base) path-part
      (= path-part "/")            normalized-base
      :else                        (join-paths normalized-base path-part))))

(defn make-path [& segments]
  (apply with-base-path segments))

(defn create-confirmation-url [token]
  (join-url (config :base-url)
            (normalize-path (config :base-path))
            (str "confirm?token=" (java.net.URLEncoder/encode token "UTF-8"))))

;; Returns Authorization header value for Mailgun API requests
(def get-mailgun-auth-header
  (memoize
   #(let [auth-string  (str "api:" (config :mailgun-api-key))
          auth-bytes   (.getBytes auth-string)
          encoder      (java.util.Base64/getEncoder)
          encoded-auth (.encodeToString encoder auth-bytes)]
      (str "Basic " encoded-auth))))

(defn get-mailgun-member-url [email]
  (format "%s/lists/%s/members/%s"
          (config :mailgun-api-endpoint)
          (config :mailgun-list-id)
          (java.net.URLEncoder/encode email "UTF-8")))

(defn make-mailgun-request [method url body-params]
  (let [auth-header  (get-mailgun-auth-header)
        request-opts (cond-> {:headers {"Authorization" auth-header} :throw false}
                       body-params
                       (assoc :headers {"Authorization" auth-header
                                        "Content-Type"  "application/x-www-form-urlencoded"}
                              :body body-params))
        http-fn      (get {:get http/get :post http/post :delete http/delete} method)]
    (try
      (http-fn url request-opts)
      (catch Exception e
        (log/error e (str "Mailgun " (name method) " error: " url))
        {:error       true
         :exception   (.getMessage e)
         :stack-trace (with-out-str (.printStackTrace e))}))))

(def subscription-count (atom 0))

(defn warn-new-subscription! []
  (let [new-count (swap! subscription-count inc)]
    (when (zero? (mod new-count 10))
      (log/info (format "%d new subscriptions" new-count)))))

(defn manage-mailgun-subscription [action email]
  (log/info (if (= action :subscribe) "Subscribing" "Unsubscribing") "email:" email)
  (let [[method url body-params]
        (case action
          :subscribe   [:post
                        (format "%s/lists/%s/members"
                                (config :mailgun-api-endpoint)
                                (config :mailgun-list-id))
                        (format "address=%s&subscribed=yes&upsert=yes"
                                (java.net.URLEncoder/encode email "UTF-8"))]
          :unsubscribe [:delete (get-mailgun-member-url email) nil])
        _        (log/debug "Making" (name method) "request to Mailgun API:" url (when body-params body-params))
        response (make-mailgun-request method url body-params)]
    (log/debug "Mailgun API response status:" (:status response))
    (log/debug "Mailgun API response body:" (:body response))
    (cond
      (:error response)
      {:success false
       :message "Connection error. Please try again later."
       :debug   response}
      (< (:status response) 300)
      (do
        (when (= action :subscribe)
          (warn-new-subscription!)
          (log/info "Successfully subscribed email:" email))
        (when (= action :unsubscribe)
          (log/info "Successfully unsubscribed email:" email))
        {:success true})
      (and (= action :unsubscribe) (= (:status response) 404))
      (do
        (log/debug "Email not found for unsubscription:" email)
        {:success   false
         :not_found true
         :message   "Email address not found in subscription list."})
      :else
      (do
        (log/error "Failed to" (name action) "email:" email "- Status:" (:status response))
        (log/error "Error response:" (:body response))
        {:success false
         :message (str "Failed to " (name action) ". Please try again later.")
         :debug   {:status (:status response)
                   :body   (:body response)}}))))

(defn process-confirmation-token [token-key]
  (if-let [token-data (validate-token token-key)]
    (let [token-type    (:type token-data)
          email         (get-in token-data [:data :email])
          consume-token #(when-let [token-data (validate-token % :consume true)]
                           (:data token-data))]
      (case token-type
        :subscribe
        (let [result (manage-mailgun-subscription :subscribe email)]
          (when (:success result)
            (consume-token token-key))
          (assoc result
                 :email email
                 :action :subscribe
                 :confirm-type :subscribe-confirmation-success))
        :unsubscribe
        (let [result (manage-mailgun-subscription :unsubscribe email)]
          (when (:success result)
            (consume-token token-key))
          (assoc result
                 :email email
                 :action :unsubscribe
                 :confirm-type :unsubscribe-confirmation-success))
        {:success false :invalid_token true :message "Unknown token type"}))
    {:success false :invalid_token true :message "Invalid or expired token"}))

(def index-template
  "<!DOCTYPE html>
<html lang=\"{{lang}}\">
<head>
  <meta charset=\"UTF-8\">
  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">
  <title>{{page.title}}{% if list-name|not-empty %} - {{list-name}}{% endif %}</title>
  <link rel=\"icon\" href=\"data:image/png;base64,iVBORw0KGgo=\">
  <link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/npm/@picocss/pico@2/css/pico.min.css\">
  <style>
  .container {max-width: 800px; padding: 2rem 1rem; margin: 0 auto;}
  @media (max-width: 768px) {.container {width: 100%; padding: 1rem;}}
  .success {border-left: 5px solid var(--pico-color-green-550);}
  .error {border-left: 5px solid var(--pico-color-red-550);}
  .warning {border-left: 5px solid var(--pico-color-yellow-550);}
  .info {border-left: 5px solid var(--pico-color-blue-550);}
  button.primary {background-color: var(--pico-primary-background); color: var(--pico-primary-inverse);}
  button.secondary {background-color: var(--pico-secondary-background); color: var(--pico-secondary-inverse);}
  .visually-hidden {position: absolute;left: -9999px; height: 1px; width: 1px; overflow: hidden;}
  .card {padding: 2rem; margin-bottom: 1rem;}
  .footer {text-align: center;font-size: .8rem;}
  </style>
</head>
<body>
  <main class=\"container\">
    <!-- Message block (shown when there's a message to display) -->
    {% if message %}
    <article class=\"card {{message-type}}\">
      <h1>{{heading}}</h1>
      <p>{{message|safe}}</p>
      <br/>
      <div style=\"text-align: center;\">
        <a href=\"{{base-path}}\" class=\"secondary\" role=\"button\">{{messages.back-to-homepage}}</a>
      </div>
    </article>
    <footer class=\"footer\">{{page.footer|safe}}</footer>
    {% endif %}
    <!-- Subscription form (shown when there's no message) -->
    {% if show-form %}
    <article>
      <div>
        <h1>{% firstof list-name page.heading %}</h1>
        <p>{{page.subheading}}</p>
        <form action=\"{{subscribe_path}}\" method=\"post\">
          <input type=\"email\" id=\"email\" name=\"email\" placeholder=\"{{form.email-placeholder}}\" required>
          <input type=\"hidden\" name=\"csrf_token\" value=\"{{csrf_token}}\">
          <div class=\"visually-hidden\">
            <label for=\"website\">{{form.website-label}}</label>
            <input type=\"text\" id=\"website\" name=\"website\" autocomplete=\"off\">
          </div>
          <div class=\"grid\">
            <button type=\"submit\" name=\"action\" value=\"subscribe\" class=\"primary\">{{form.subscribe-button}}</button>
            <button type=\"submit\" name=\"action\" value=\"unsubscribe\" class=\"secondary\">{{form.unsubscribe-button}}</button>
          </div>
        </form>
      </div>
    </article>
    <footer class=\"footer\">{{page.footer|safe}}</footer>
    {% endif %}
  </main>
</body>
</html>")

(defn escape-html [s]
  (when (not-empty s)
    (-> s
        (str/replace "&" "&amp;")
        (str/replace "<" "&lt;")
        (str/replace ">" "&gt;")
        (str/replace "\"" "&quot;")
        (str/replace "'" "&#39;"))))

(defn render-html
  "Render function for form and confirmation messages."
  [strings lang & {:keys [csrf-token message-type heading message show-form] :or {show-form true}}]
  (selmer/render
   (or (config :index-tpl) index-template)
   (cond-> {:lang           lang
            :list-name      (config :mailgun-list-name)
            :page           (:page strings)
            :messages       (:messages strings)
            :form           (:form strings)
            :base-path      (make-path "")
            :subscribe_path (make-path "subscribe")
            :show-form      show-form}
     ;; Add message-related parameters if showing a message
     message (assoc :message-type message-type
                    :heading heading
                    :message message)
     ;; Add CSRF token if displaying form
     (and show-form csrf-token)
     (assoc :csrf_token csrf-token))))

(defn result-html [lang type message & args]
  (let [strings (get-ui-strings (or lang :en))
        heading (get-in strings [:messages (keyword message)])
        message (get-in strings [:messages (keyword (str (name message) "-message"))])]
    (render-html strings
                 lang
                 :message-type type
                 :heading heading
                 :message (if (seq args) (apply format message (map escape-html args)) (or message ""))
                 :show-form false)))

(def base-security-headers
  {"X-Content-Type-Options" "nosniff"
   "X-Frame-Options"        "DENY"})

(def security-headers
  (merge base-security-headers
         {"Content-Security-Policy" "default-src 'self';script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net;style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net;img-src 'self' data: https://cdn.jsdelivr.net;font-src 'self' https://cdn.jsdelivr.net;"}))

(def security-headers-self
  (merge base-security-headers
         {"Content-Security-Policy" "default-src 'self';"}))

(defn make-response [status type lang message & args]
  {:status  status
   :headers (merge {"Content-Type" "text/html; charset=UTF-8"} security-headers)
   :body    (apply result-html lang type message args)})

(defn determine-language [req]
  (let [accept-language (get-in req [:headers "accept-language"] "")]
    (cond (str/includes? accept-language "fr") :fr
          :else                                :en)))

(defn handle-error [req e debug-info]
  (log/error "Error:" (str e))
  (log/error "Stack trace:" (with-out-str (.printStackTrace e)))
  (let [lang (determine-language req)]
    {:status  500
     :headers (merge {"Content-Type" "text/html; charset=UTF-8"} security-headers)
     :body    (result-html lang "error" :operation-failed debug-info)}))

(defn check-if-subscribed [email]
  (log/debug "Checking if email is already subscribed:" email)
  (let [url      (get-mailgun-member-url email)
        _        (log/debug "Making request to check subscription status:" url)
        response (make-mailgun-request :get url nil)]
    (log/debug "Mailgun API check response status:" (:status response))
    (log/debug "Mailgun API check response body:" (:body response))
    (and (not (:error response))
         (= 200 (:status response)))))

(defn send-confirmation-email [{:keys [email token lang action]
                                :or   {action :subscribe lang :en}}]
  (let [strings     (get-ui-strings lang)
        confirm-url (create-confirmation-url token)
        from        (or (config :subscribe-smtp-from) "noreply@example.com")
        email-templates
        {:subscribe
         {:subject   (get-in strings [:emails :subscription-confirm-subject])
          :body-text (get-in strings [:emails :subscription-confirm-body-text])
          :body-html (get-in strings [:emails :subscription-confirm-body-html])}
         :unsubscribe
         {:subject   (get-in strings [:emails :unsubscribe-confirm-subject])
          :body-text (get-in strings [:emails :unsubscribe-confirm-body-text])
          :body-html (get-in strings [:emails :unsubscribe-confirm-body-html])}}
        {:keys [subject body-text body-html]}
        (get email-templates action (get email-templates :subscribe))]
    (log/info (str "Sending " (name action) " confirmation email to:") email)
    (log/debug "Confirmation URL:" confirm-url)
    (try
      (let [smtp-host (config :subscribe-smtp-host)
            smtp-port (config :subscribe-smtp-port)
            smtp-user (config :subscribe-smtp-user)
            smtp-pass (config :subscribe-smtp-pass)]
        (if (and smtp-host smtp-port smtp-user smtp-pass)
          (let [list-description (or (not-empty (config :mailgun-list-name))
                                     (config :mailgun-list-id))
                result           (mail/send-mail
                                  {:from     from
                                   :to       [email]
                                   :subject  (format subject list-description)
                                   :text     (format body-text email confirm-url)
                                   :html     (format body-html email confirm-url)
                                   :host     smtp-host
                                   :port     (Integer/parseInt (or smtp-port "587"))
                                   :username smtp-user
                                   :password smtp-pass})]
            (log/debug "Sending email result:" result)
            (log/info (str (name action) " confirmation email sent to") email)
            {:success true})
          (do
            (log/error (str "SMTP configuration missing. Cannot send "
                            (name action) " confirmation email."))
            {:success false
             :message "Email configuration missing"})))
      (catch Exception e
        (log/error (str "Failed to send " (name action)
                        " confirmation email:") (.getMessage e))
        {:success false
         :message (.getMessage e)}))))

(defn handle-subscription-request [email lang]
  (log/info "Handling subscription request for:" email)
  (cond
    (check-if-subscribed email)
    {:already_subscribed true}
    (has-pending-confirmation? email :subscribe)
    {:confirmation_pending true}
    :else
    (let [token  (create-token :subscribe {:email email})
          result (send-confirmation-email
                  {:email  email
                   :token  token
                   :lang   lang
                   :action :subscribe})]
      (if (:success result)
        {:confirmation_sent true}
        {:message (:message result)}))))

(defn handle-unsubscribe-request [email lang]
  (log/info "Handling unsubscribe request for:" email)
  (cond
    (not (check-if-subscribed email))
    {:not_subscribed true}
    (has-pending-confirmation? email :unsubscribe)
    {:confirmation_pending true}
    :else
    (let [token  (create-token :unsubscribe {:email email})
          result (send-confirmation-email
                  {:email  email
                   :token  token
                   :lang   lang
                   :action :unsubscribe})]
      (if (:success result)
        {:confirmation_sent true}
        {:message (:message result)}))))

(defn parse-form-data [request]
  (let [content-type (get-in request [:headers "content-type"] "")
        is-form      (str/includes? content-type "application/x-www-form-urlencoded")]
    (if-not (and (:body request) is-form)
      (do (log/debug "Not a form submission or no body") {})
      (try
        (letfn [(decode-value [s]
                  (try (java.net.URLDecoder/decode s "UTF-8")
                       (catch Exception _ "")))
                (parse-pair [pair]
                  (let [[k v] (str/split pair #"=" 2)
                        key   (keyword (decode-value k))
                        value (if v (decode-value v) "")]
                    [key value]))]
          (let [body   (slurp (:body request))
                pairs  (str/split body #"&")
                pairs  (filter #(not (str/blank? %)) pairs)
                result (into {} (map parse-pair pairs))]
            (log/debug "Parsed form data:" (pr-str result))
            result))
        (catch Exception e
          (log/error "Form parsing error:" (.getMessage e)))))))

(defn parse-query-params [req]
  (let [query-params (:query-params req)
        uri-params   (when-let [query (:query-string req)]
                       (when-let [token (last (re-matches #"^token=(.+)$" query))]
                         {:token (java.net.URLDecoder/decode token "UTF-8")}))]
    (or query-params uri-params)))

(defn process-subscription-action [lang action email]
  (case action
    "subscribe"
    (let [result (handle-subscription-request email lang)]
      (cond
        (:already_subscribed result)
        (make-response 200 "success" lang :already-subscribed email)
        (:confirmation_pending result)
        (make-response 200 "info" lang :confirmation-pending email)
        (:confirmation_sent result)
        (make-response 200 "info" lang :confirmation-sent email)
        :else
        {:status  400
         :headers (merge {"Content-Type" "text/html; charset=UTF-8"} security-headers)
         :body    (result-html lang "error" :operation-failed (:message result))}))
    "unsubscribe"
    (let [result (handle-unsubscribe-request email lang)]
      (cond
        (:not_subscribed result)
        (make-response 200 "warning" lang :not-subscribed email)
        (:confirmation_pending result)
        (make-response 200 "info" lang :confirmation-pending email)
        (:confirmation_sent result)
        (make-response 200 "info" lang :confirmation-sent email)
        :else
        {:status  400
         :headers (merge {"Content-Type" "text/html; charset=UTF-8"} security-headers)
         :body    (result-html lang "error" :confirmation-email-failed (:message result))}))
    (make-response 400 "error" lang :operation-failed)))

(defn get-client-ip [req]
  (or (get-in req [:headers "x-forwarded-for"])
      (get-in req [:headers "x-real-ip"])
      (:remote-addr req)
      "unknown-ip"))

(defn handle-index [req]
  (let [lang       (determine-language req)
        strings    (get-ui-strings lang)
        client-ip  (get-client-ip req)
        csrf-token (get-or-create-csrf-token client-ip)]
    (log/debug "Using CSRF token for IP" client-ip ":" csrf-token)
    {:status  200
     :headers (merge {"Content-Type" "text/html; charset=UTF-8"} security-headers)
     :body    (render-html strings lang :csrf-token csrf-token :show-form true)}))

(defn rate-limited? [ip]
  (let [now             (System/currentTimeMillis)
        window-start    (- now rate-limit-window)
        requests        (get @ip-request-log ip [])
        recent-requests (filter #(>= % window-start) requests)]
    ;; Prune old entries periodically
    (when (or (> (- now @last-pruned-time) rate-limit-window)
              (> (count @ip-request-log) 1000))
      (swap! ip-request-log
             (fn [log-map]
               (reduce-kv (fn [m k v] (assoc m k (filter #(>= % window-start) v)))
                          {} log-map)))
      (reset! last-pruned-time now))
    ;; Update the request log with the current timestamp
    (swap! ip-request-log update ip #(conj (or % []) now))
    ;; Prune old entries every 1000 IP requests
    (when (> (count @ip-request-log) 1000)
      (swap! ip-request-log
             (fn [log-map]
               (reduce-kv (fn [m k v]
                            (assoc m k (filter #(>= % window-start) v)))
                          {}
                          log-map))))
    (> (count recent-requests) max-requests-per-window)))

(defn handle-subscribe [req]
  (log/debug "Request method:" (:request-method req))
  (log/debug "Headers:" (pr-str (:headers req)))
  (try
    (let [form-data       (parse-form-data req)
          email           (some-> (:email form-data) str/trim str/lower-case)
          action          (or (:action form-data) "subscribe")
          client-ip       (get-client-ip req)
          lang            (determine-language req)
          form-csrf-token (:csrf_token form-data)]
      (log/debug "Parsed form data:" (pr-str form-data))
      (log/debug "Email from form:" email)
      (log/debug "Action from form:" action)
      (log/debug "CSRF token from form:" form-csrf-token)
      (if-not email
        (make-response 400 "error" lang :invalid-email "No email provided")
        ;; CSRF Protection check
        (if-not (validate-csrf-token form-csrf-token client-ip)
          (do
            (log/warn "CSRF token validation failed")
            (make-response 403 "error" lang :csrf-invalid))
          ;; Anti-spam: rate limiting
          (if (rate-limited? client-ip)
            (do
              (log/warn "Rate limit exceeded for IP:" client-ip)
              (make-response 429 "error" lang :rate-limit))
            ;; Anti-spam: honeypot check
            (if (not (str/blank? (str (:website form-data))))
              (do
                (log/warn "Spam detected: honeypot field filled from IP:" client-ip)
                (make-response 400 "error" lang :spam-detected))
              ;; Email validation
              (if (s/valid? ::subscription-form form-data)
                (process-subscription-action lang action email)
                (let [explain (s/explain-str ::subscription-form form-data)]
                  (log/error "Invalid form submission:" explain)
                  (make-response 400 "error" lang :invalid-email email))))))))
    (catch Throwable e
      (handle-error req e (str "Request method: " (name (:request-method req)) "\n"
                               "Headers: " (pr-str (:headers req)))))))

(defn handle-confirmation [req]
  (try
    (let [token (-> req :query-params :token)
          lang  (determine-language req)]
      (log/debug "Processing confirmation token: " token)
      (if (str/blank? token)
        ;; No token provided
        {:status  400
         :headers {"Content-Type" "text/html; charset=UTF-8"}
         :body    (result-html lang "error" :csrf-invalid)}
        ;; Process token directly
        (let [result   (process-confirmation-token token)
              success? (:success result)]
          (log/debug "Token processing result: " result)
          {:status  (if success? 200 400)
           :headers {"Content-Type" "text/html; charset=UTF-8"}
           :body    (result-html
                     lang
                     (if success? "success" "error")
                     (if success?
                       (keyword (str (name (:action result)) "-confirmation-success"))
                       :confirmation-error))})))
    (catch Exception e
      (log/error "Error processing confirmation: " e)
      {:status  500
       :headers {"Content-Type" "text/html; charset=UTF-8"}
       :body    (result-html :en "error" :operation-failed)})))

(def routes
  [{:path "/" :method :get :response handle-index}
   {:path "/subscribe" :method :post :response handle-subscribe}
   {:path "/confirm" :method :get :response handle-confirmation}
   {:path     "/robots.txt" :method :get
    :response {:status  200
               :headers {"Content-Type" "text/plain; charset=UTF-8"}
               :body    "User-agent: *\nDisallow: /"}}])

(defn app [req]
  (let [uri             (:uri req)
        normalized-uri  (normalize-url uri)
        query-params    (parse-query-params req)
        req-with-params (assoc req :query-params query-params)]
    (try
      (log/debug "Processing request:" (:request-method req) uri)
      (log/debug "Normalized path:" normalized-uri)
      (log/debug "Query params:" (pr-str query-params))
      (let [response (ruuter/route routes req-with-params)]
        (or response
            (do
              (log/debug "Not found:" (:request-method req) uri)
              {:status  404
               :headers (merge {"Content-Type" "text/html; charset=UTF-8"}
                               security-headers-self)
               :body    (result-html :en "error" :operation-failed)})))
      (catch Throwable e (handle-error req e (str "URI: " uri))))))

(defn merge-ui-strings! [config-data]
  (when-let [config-ui-strings (:ui-strings config-data)]
    (swap! app-config update :ui-strings
           (fn [original]
             (merge-with (fn [orig new] (merge-with merge orig new))
                         original
                         config-ui-strings)))
    (log/info "Merged UI strings from configuration file")))

(defn update-config-from-file! [file-path]
  (when file-path
    (log/info "Using configuration file:" file-path)
    (when-let [config-data (edn/read-string (slurp file-path))]
      (if-not (validate-config config-data)
        (do (log/error "Invalid configuration data")
            (System/exit 0))
        (do ;; Merge UI strings first to handle the nested structure
          (merge-ui-strings! config-data)
          ;; Handle path normalization for specific fields - FIXME: needed?
          (let [processed-config
                (cond-> (dissoc config-data :ui-strings)
                  (:base-path config-data)
                  (update :base-path normalize-path)
                  (:base-url config-data)
                  (update :base-url normalize-url))]
            ;; Log what we're updating
            (doseq [k (keys processed-config)]
              (log/info "Updating config:" k))
            ;; Update the config with the processed values
            (swap! app-config merge processed-config))
          ;; Load templates from files specified in config
          (when-let [index-file (:index-tpl config-data)]
            (when-let [index-content (slurp index-file)]
              (swap! app-config assoc :index-tpl index-content)))
          ;; Update logging configuration if log-file is specified
          (when-let [log-file (:log-file config-data)]
            (log/merge-config!
             {:appenders {:spit (log/spit-appender {:fname log-file})}})))))))

(defn -main [& args]
  (let [opts (cli/parse-opts args {:spec cli-options})
        port (get opts :port 8080)]
    (when (:help opts) (print-usage))
    ;; Update base-url with specified port when provided
    (when-let [specified-port (get opts :port)]
      (swap! app-config assoc :base-url (generate-default-base-url specified-port)))
    ;; Set base-url from command line if provided
    (when-let [conf-url (:base-url opts)]
      (swap! app-config assoc :base-url conf-url)
      (log/info "Setting base-url from command line:" conf-url))
    ;; Set base-path from command line if provided
    (when-let [path (:base-path opts)]
      (swap! app-config assoc :base-path (normalize-path path))
      (log/info "Setting base-path from command line:" path))
    ;; Process template files if provided via command line
    (when-let [index-file (:index-tpl opts)]
      (when-let [index-content (slurp index-file)]
        (swap! app-config assoc :index-tpl index-content)
        (log/info "Loaded index template from file:" index-file)))
    ;; Process configuration file if provided (this overrides individual settings)
    (when-let [config-path (:config opts)]
      (update-config-from-file! config-path))
    (when-not (config :mailgun-api-key)
      (log/error "MAILGUN_API_KEY not set")
      (System/exit 0))
    ;; Configure Timbre logging
    (let [appenders (merge {:println (log/println-appender {:stream :auto})}
                           (when-let [f (get opts :log-file)]
                             {:spit (log/spit-appender {:fname f})}))]
      (log/merge-config!
       {:min-level (keyword (get opts :log-level)) :appenders appenders}))
    ;; Start the server
    (log/info (str "Starting server on http://localhost:" port))
    (log/info (str "Base path: " (if (str/blank? (config :base-path)) "[root]" (config :base-path))))
    (server/run-server app {:port port})
    ;; Keep the server running
    @(promise)))

;; Main entry point
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
