;; Copyright (c) 2019-2020 DINSIC, Bastien Guerry <bastien.guerry@data.gouv.fr>
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSES/EPL-2.0.txt

(ns choices.core
  (:require-macros [choices.macros :refer [inline-yaml-resource]])
  (:require [reagent.core :as reagent]
            [reagent.dom]
            [reagent.format :as fmt]
            [reagent.session :as session]
            [reitit.frontend :as rf]
            [reitit.frontend.easy :as rfe]
            [choices.i18n :as i18n]
            [cljsjs.clipboard]
            [clojure.string :as string]
            [markdown-to-hiccup.core :as md]
            [taoensso.tempura :refer [tr]]))

;; General configuration
(def config (inline-yaml-resource "config.yml"))

(def conditional-score-output (:conditional-score-output config))
(def show-summary (:display-summary config))

;; UI variables
(def bigger {:font-size "2em" :text-decoration "none"})

;; Modal variables
(def show-help (reagent/atom (:display-help config)))

(def show-modal (reagent/atom false))
(def show-summary-answers (reagent/atom true))
(def modal-message (reagent/atom ""))

;; home-page and start-page
(def home-page
  (first (remove nil? (map #(when (:home-page %) (keyword (:name %)))
                           (:tree config)))))
(def start-page
  (first (remove nil? (map #(when (:start-page %) (keyword (:name %)))
                           (:tree config)))))

(defn md-to-string [^string s]
  (-> s (md/md->hiccup) (md/component)))

;; History-handling variables
(def history (reagent/atom [{:score (:score-variables config)}]))
(def hist-to-redo (reagent/atom {}))
(def hist-to-add (reagent/atom {}))

;; Localization variables
(def localization-custom
  (into {} (map (fn [locale] {(key locale)
                              (merge (val locale) (:ui-strings config))})
                i18n/localization)))

(def lang (keyword (or (not-empty (:locale config)) "en-GB")))
(def opts {:dict localization-custom})
(def i18n (partial tr opts [lang]))

;; Create routes
(def routes
  (into [] (for [n (:tree config)] [(:name n) (keyword (:name n))])))

;; Define multimethod for later use in `create-page-contents`
(defmulti page-contents identity)

;; Create a copy-to-clipboard component
(defn clipboard-button [label target]
  (let [clipboard-atom (reagent/atom nil)]
    (reagent/create-class
     {:display-name "clipboard-button"
      :component-did-mount
      #(let [clipboard (new js/ClipboardJS (reagent.dom/dom-node %))]
         (reset! clipboard-atom clipboard))
      :component-will-unmount
      #(when-not (nil? @clipboard-atom)
         (reset! clipboard-atom nil))
      :reagent-render
      (fn []
        [:a.button.text
         {:title                 (i18n [:copy-to-clipboard])
          :style                 bigger
          :data-clipboard-target target}
         label])})))

(defn strip-html-tags [^string s]
  (if (string? s) (string/replace s #"<([^>]+)>" "") s))

(defn sort-map-by-score-values [m]
  (into (sorted-map-by
         (fn [k1 k2] (compare [(:value (get m k2)) k2]
                              [(:value (get m k1)) k1])))
        m))

;; Create all the pages
(defn create-page-contents [{:keys [done name text help no-summary
                                    force-help choices]}]
  (defmethod page-contents (keyword name) []
    [:div
     (when (not-empty (:header config))
       [:section {:class (str "hero " (:color (:header config)))}
        [:div.hero-body
         [:div.container
          [:div.columns
           (let [logo (:logo (:header config))]
             (when (not-empty logo)
               [:div.column
                [:figure.media-left
                 [:p.image.is-128x128
                  [:a {:href (rfe/href home-page)}
                   [:img {:src logo}]]]]])
             [:div.column
              {:class (if (not-empty logo)
                        "has-text-right"
                        "has-text-centered")}
              [:h1.title (:title (:header config))]
              [:br]
              [:h2.subtitle
               (md-to-string (:subtitle (:header config)))]])]]]])
     [:div.container
      [:div {:class (str "modal " (when @show-modal "is-active"))}
       [:div.modal-background]
       [:div.modal-content
        [:div.box
         [:div.title (i18n [:attention])]
         [:p @modal-message]
         [:br]
         [:div.has-text-centered
          [:a.button.is-medium.is-warning
           {:on-click #(reset! show-modal false)}
           (i18n [:ok])]]]]
       [:button.modal-close.is-large
        {:aria-label "close"
         :on-click   #(reset! show-modal false)}]]
      [:div.section
       [:div.level
        [:h1.title.level-item.has-text-centered (md-to-string text)]
        (if-not done
          ;; Not done: display the help button
          [:a.level-item.button.is-text
           {:style    bigger
            :title    (i18n [:display-help])
            :on-click #(swap! show-help not)}
           "💬"]
          ;; Done: display the copy-to-clipboard button
          [:div.level-item
           [:a.button.is-text
            {:style    bigger
             :title    (i18n [:toggle-summary-style])
             :on-click #(swap! show-summary-answers not)} "🔗"]
           [clipboard-button "📋" "#copy-this"]])]
       (when (and (or force-help @show-help)
                  (not-empty help))
         [:div.notification (md-to-string help)])
       (if-not done
         ;; Not done: display the choices
         [:div.tile.is-ancestor
          (for [{:keys [answer goto explain color summary score] :as c}
                choices]
            ^{:key c}
            [:div.tile.is-parent
             [:a.tile.is-child
              {:style {:text-decoration "none"}
               :href  (rfe/href (keyword goto))
               :on-click
               #(do (when (vector? summary)
                      (reset! show-modal true)
                      (reset! modal-message (md-to-string (peek summary))))
                    (reset! hist-to-add
                            (merge
                             {:score
                              (merge-with
                               (fn [a b] {:display               (:display a)
                                          :as-top-result-display (:as-top-result-display a)
                                          :value                 (+ (:value a) (:value b))})
                               (:score (peek @history))
                               score)}
                             {:questions (when-not no-summary [text answer])}
                             {:answers summary})))}
              [:div.card-content.tile.is-parent.is-vertical
               [:div {:class (str "tile is-child box title notification " color)}
                (md-to-string answer)]
               (when (and explain @show-help)
                 [:div.tile.is-child.subtitle
                  (md-to-string explain)])]]])]
         ;; Done: display the final summary-answers
         [:div
          [:div.tile.is-ancestor {:id "copy-this"}
           [:div.tile.is-parent.is-vertical.is-12
            ;; Display score
            (if-let [scores (:score (peek @history))]
              [:div
               (when (:display-score config)
                 [:div.is-6
                  ;; Optional, mainly for debuggin purpose
                  (when (:display-score-details config)
                    (for [row-score (partition-all 4 scores)]
                      ^{:key (pr-str row-score)}
                      [:div.tile.is-parent
                       (for [s row-score]
                         ^{:key (pr-str s)}
                         [:div.tile.is-child.is-3.box
                          (str (:display (val s)) ": " (:value (val s)))])]))
                  ;; Only when no score-results
                  (when (and (not conditional-score-output)
                             (:display-score-main-result config))
                    (let [final-scores  (sort-map-by-score-values scores)
                          last-score    (first final-scores)
                          butlast-score (second final-scores)]
                      (when (> (:value (val last-score)) (:value (val butlast-score)))
                        (when-let [s (:as-top-result-display (val last-score))]
                          [:div.tile.is-parent.is-6
                           [:p.tile.is-child.box.is-warning.notification
                            (:as-top-result-display s)]]))))
                  ;; Only when score-results is defined
                  (when conditional-score-output
                    (let [s     (apply merge (map (fn [[k v]] {k (:value v)}) scores))
                          out   (atom "")
                          notif (atom "")]
                      (do (doseq [ss   conditional-score-output
                                  :let [cas (last ss)
                                        notification (:notification cas)
                                        message (:message cas)
                                        conditions (dissoc cas :message :notification)]]
                            (doseq [cnd conditions :let [c (val cnd)]]
                              (when (every? true? (map (fn [[k v]] (>= (k s) v)) c))
                                (reset! out message)
                                (reset! notif notification))))
                          [:div.tile.is-parent.is-12
                           [:p {:class (str "tile is-child box "
                                            (or (not-empty @notif) "is-info")
                                            " notification subtitle")}
                            @out]])))])]
              [:br])
            ;; Display answers
            (when  show-summary
              (for [o (if @show-summary-answers
                        (reverse (:answers (peek @history)))
                        (reverse (:questions (peek @history))))]
                ^{:key o}
                (cond
                  (and (string? o) (not-empty o))
                  [:div.tile.is-parent
                   [:div.tite.is-child.notification
                    [:div.subtitle (md-to-string o)]]]
                  (not-empty (butlast o))
                  [:div.tile.is-parent.is-horizontal.notification
                   (for [n (butlast o)]
                     ^{:key n}
                     (when (not-empty n)
                       [:div.tile.is-child.subtitle (md-to-string n)]))
                   (when-let [a (not-empty (peek o))]
                     [:div.tile.is-child.subtitle.has-text-centered.has-text-weight-bold.is-size-4
                      (md-to-string a)])])))]]
          [:div.level-right
           [:a.button.level-item
            {:style bigger
             :title (i18n [:redo])
             :href  (rfe/href start-page)} "🔃"]
           (when (not-empty (:mail-to config))
             [:a.button.level-item
              {:style bigger
               :title (i18n [:mail-to-message])
               :href  (str "mailto:" (:mail-to config)
                           "?subject=" (i18n [:mail-subject])
                           "&body="
                           (string/replace
                            (fmt/format (i18n [:mail-body])
                                        (string/join "%0D%0A%0D%0A"
                                                     (map strip-html-tags
                                                          (flatten (:answers (peek @history))))))
                            #"[\n\t]" "%0D%0A%0D%0A"))}
              "📩"])]])]]
     (when (not-empty (:footer config))
       [:section.footer
        [:div.content.has-text-centered
         (md-to-string (:text (:footer config)))
         (when-let [c (not-empty (:contact (:footer config)))]
           [:p (i18n [:contact-intro])
            [:a {:href (str "mailto:" c)} c]])]])]))

;; Create all the pages from `config/tree`
(dorun (map create-page-contents (:tree config)))

;; Create component to mount the current page
(defn current-page []
  (let [page (or (session/get :current-page) home-page)]
    [:div ^{:key page} [page-contents page]]))

(defn on-navigate [match]
  (let [target-page (:name (:data match))
        prev        (peek @history)]
    (cond
      ;; Reset history?
      (= target-page start-page)
      (do (reset! history [{:score (:score-variables config)}])
          (reset! hist-to-redo {})
          (reset! hist-to-add {}))
      ;; History backward?
      (= target-page (first (:page (peek @history))))
      (reset! history (into [] (butlast @history)))
      ;; History forward?
      (= target-page (first (:page @hist-to-redo)))
      (swap! history conj @hist-to-redo)
      :else
      (swap! history
             conj {:page      (conj (:page prev) (session/get :current-page))
                   :questions (conj (:questions prev) (:questions @hist-to-add))
                   :answers   (conj (:answers prev) (:answers @hist-to-add))
                   :score     (conj (:score prev) (:score @hist-to-add))}))
    (reset! hist-to-redo (peek @history))
    (session/put! :current-page target-page)))

(defn ^:export init []
  (rfe/start!
   (rf/router routes)
   on-navigate
   {:use-fragment true})
  (reagent.dom/render
   [current-page]
   (. js/document (getElementById "app"))))
