(ns voterx.views.d3
  (:require
    [cljsjs.d3]
    [cljs.test]
    [clojure.string :as string]
    [reagent.core :as reagent]
    [reagent.dom :as dom]
    [goog.crypt :as crypt]
    [devcards.core])
  (:require-macros
    [devcards.core :refer [defcard-rg]]
    [reagent.ratom :refer [reaction]])
  (:import
    [goog.crypt Md5]))

(defn update-simulation [simulation nodes edges]
  (let [particles (concat nodes edges)
        existing-particles (.nodes simulation)
        ids (set (map :db/id particles))
        kept-particles (filter #(contains? ids (.-id %)) existing-particles)
        existing-ids (set (keys (.-idxs simulation)))
        added-particles (clj->js (remove #(contains? existing-ids (:db/id %)) particles))
        js-particles (concat kept-particles added-particles)
        idxs (zipmap (map #(.-id %) js-particles) (range))]
    (.nodes simulation
            (clj->js
              (map-indexed
                (fn [idx particle]
                  (set! (.-index particle) idx)
                  particle)
                js-particles)))
    (.force simulation "link"
            (js/d3.forceLink
              (clj->js
                (map-indexed
                  (fn [idx x]
                    (assoc x :index idx))
                  (apply
                    concat
                    (for [{:keys [db/id from to]} edges]
                      [{:link [from id]
                        :source (idxs from)
                        :target (idxs id)}
                       {:link [id to]
                        :source (idxs id)
                        :target (idxs to)}]))))))
    (set! (.-name simulation) "Untitled")
    (set! (.-idxs simulation) idxs)
    (set! (.-paths simulation)
          (for [{:keys [db/id from to]} edges]
            [(idxs from)
             (idxs id)
             (idxs to)]))))

(defn restart-simulation [simulation]
  (doto simulation
    (.restart)
    (.alpha 1)))

(defn color-for [uid]
  (let [h (hash uid)]
    [(bit-and 0xff h)
     (bit-and 0xff (bit-shift-right h 8))
     (bit-and 0xff (bit-shift-right h 16))]))

(defn scale-rgb [rgb rank-scale]
  (map int (map * rgb (repeat (+ 0.9 (* 0.5 rank-scale))))))

(defn scale-dist [n rank-scale]
  (+ 5 (* (min (max n 10) 30) rank-scale)))

(defn rgb [[r g b]]
  (str "rgb(" r "," g "," b ")"))

(defn md5-hash [s]
  (let [md5 (Md5.)]
    (.update md5 s)
    (crypt/byteArrayToHex (.digest md5))))

(defn gravatar-background [id r email]
  (let [guid (md5-hash (string/trim email))]
    [:g
     [:defs
      [:pattern
       {:id guid
        :pattern-units "userSpaceOnUse"
        :height (* r 2)
        :width (* r 2)
        :pattern-transform (str "translate(" (- r) "," (- r) ")")}
       [:image
        {:height (* r 2)
         :width (* r 2)
         :xlink-href (str "http://www.gravatar.com/avatar/" guid)}]]]
     [:circle
      {:r r
       :fill (str "url(#" guid ")")}]]))

(defn stringify-points [points]
  (->> points
       (partition-all 2)
       (map #(string/join "," %))
       (string/join " ")))

(defn polygon-background [attrs points]
  [:polygon
   (merge attrs {:points (stringify-points points)})])

(defn triangle-background [attrs r]
  (let [h (Math/sqrt (- (* 4 r r) (* r r)))
        y1 (- (/ h 3))
        y2 (- (* 2 y1))
        points [(- r) y1 r y1 0 y2]]
    [polygon-background attrs points]))

(defn rect-background [attrs r]
  [:rect
   (merge attrs
          {:x (- r)
           :y (- r)
           :width (* r 2)
           :height (* r 2)})])

(defn circle-background [attrs r]
  [:circle
   (merge attrs {:r r})])

(def shapes
  {:circle circle-background
   :triangle triangle-background
   :square rect-background})

(defn shape-background [shape r node-color rank-scale selected?]
  [(shapes shape circle-background)
   {:fill (rgb (scale-rgb node-color rank-scale))
    :stroke (if selected?
              "#6699aa"
              "#9ecae1")
    :style {:cursor "pointer"}}
   r])

(def next-shape
  (zipmap (keys shapes) (rest (cycle (keys shapes)))))

(defn email? [s]
  (and (string? s)
       (->> s
            (string/trim)
            (string/upper-case)
            (re-matches #"[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,4}"))))

(defn draw-node [{:keys [id name x y pagerank shape uid]}
                 node-count
                 max-pagerank
                 simulation
                 mouse-down?
                 selected-id
                 {:keys [shift-click-node]}
                 editing]
  (let [selected? (= id @selected-id)
        rank-scale (if max-pagerank (/ pagerank max-pagerank) 0.5)
        r (scale-dist node-count rank-scale)]
    [:g
     {:transform (str "translate(" x "," y ")"
                      (when selected?
                        " scale(1.25,1.25)"))
      :on-double-click
      (fn node-double-click [e]
        (reset! selected-id nil)
        (when-let [idx (get (.-idxs simulation) id)]
          (let [particle (aget (.nodes simulation) idx)]
            (js-delete particle "fx")
            (js-delete particle "fy")))
        (restart-simulation simulation))
      :on-mouse-down
      (fn node-mouse-down [e]
        (.stopPropagation e)
        (.preventDefault e)
        (let [new-selected-id id]
          (when (and shift-click-node (.-shiftKey e) @selected-id new-selected-id)
            (shift-click-node @selected-id new-selected-id))
          (reset! selected-id new-selected-id)
          (reset! editing nil))
        (reset! mouse-down? true))}
     (if (email? name)
       [gravatar-background id r name]
       [shape-background (keyword shape) r (color-for uid) rank-scale selected?])
     [:text.unselectable
      {:text-anchor "middle"
       :font-size (min (max node-count 8) 22)
       :style {:pointer-events "none"
               :dominant-baseline "central"}}
      name]]))

(defn average [& args]
  (/ (apply + args) (count args)))

(defn rise-over-run [o a]
  (/ (* 180 (js/Math.atan2 o a)) js/Math.PI))

(defn draw-edge [[from mid to :as path] nodes simulation mouse-down? selected-id {:keys [shift-click-edge]} editing]
  (let [{x1 :x y1 :y} (get nodes from)
        {x2 :x y2 :y id :id} (get nodes mid)
        {x3 :x y3 :y} (get nodes to)
        selected? (= id (js->clj @selected-id))]
    [:g
     {:on-double-click
      (fn link-double-click [e]
        (reset! selected-id nil)
        (when-let [idx (get (.-idxs simulation) id)]
          (let [particle (aget (.nodes simulation) idx)]
            (js-delete particle "fx")
            (js-delete particle "fy")))
        (restart-simulation simulation))
      :on-mouse-down
      (fn link-mouse-down [e]
        (.stopPropagation e)
        (.preventDefault e)
        (reset! mouse-down? true)
        (reset! selected-id (.-id (aget (.nodes simulation) mid)))
        (reset! editing nil)
        (when (and shift-click-edge (.-shiftKey e))
          (shift-click-edge (get nodes mid))))
      :stroke (if selected?
                "#6699aa"
                "#9ecae1")}
     [:path
      {:fill "none"
       ;; TODO: pass in the edge
       #_#_:stroke-dasharray (when-let [w (get-in @root [:edges from to :weight])]
                           (str w "," 5))
       :d (apply str (interleave
                       ["M" "," " " "," " " ","]
                       (for [idx path
                             dim [:x :y]]
                         (get-in nodes [idx dim]))))}]
     [:polygon
      {:points "-5,-5 -5,5 7,0"
       :fill "#9ecae1"
       :transform (str "translate(" x2 "," y2
                       ") rotate(" (rise-over-run (- y3 y1) (- x3 x1)) ")"
                       (when selected?
                         " scale(1.25,1.25)"))
       :style {:cursor "pointer"}}]]))

(defn bounds [[minx miny maxx maxy] {:keys [x y]}]
  [(min minx x) (min miny y) (max maxx x) (max maxy y)])

(defn normalize-bounds [[minx miny maxx maxy]]
  (let [width (+ 100 (- maxx minx))
        height (+ 100 (- maxy miny))
        width (max width height)
        height (max height width)
        midx (average maxx minx)
        midy (average maxy miny)]
    [(- midx (/ width 2)) (- midy (/ height 2)) width height]))

(defn update-bounds [g]
  (assoc g :bounds (normalize-bounds (reduce bounds [400 400 600 600] (:nodes g)))))

(defn draw-svg [drawable simulation mouse-down? selected-id callbacks editing]
  (let [{:keys [nodes paths bounds]} @drawable
        max-pagerank (reduce max (map :pagerank nodes))
        non-edge-nodes (remove :to nodes)
        node-count (count non-edge-nodes)]
    [:svg.unselectable
     {:view-box (string/join " " bounds)
      :style {:width "100%"
              :height "100%"}}
     (doall
       (concat
         (for [path paths]
           ^{:key path}
           [draw-edge path nodes simulation mouse-down? selected-id callbacks editing])
         (for [node non-edge-nodes]
           ^{:key (:id node)}
           [draw-node node node-count max-pagerank simulation mouse-down? selected-id callbacks editing])))]))

(defn draw-graph [this drawable simulation mouse-down? selected-id editing root]
  [:div
   {:style {:height "60vh"}
    :on-mouse-down
    (fn graph-mouse-down [e]
      (.preventDefault e)
      (reset! mouse-down? true)
      (reset! selected-id nil)
      (reset! editing nil))
    :on-mouse-up
    (fn graph-mouse-up [e]
      (reset! mouse-down? nil))
    :on-mouse-move
    (fn graph-mouse-move [e]
      (let [elem (dom/dom-node this)
            r (.getBoundingClientRect elem)
            left (.-left r)
            top (.-top r)
            width (.-width r)
            height (.-height r)
            [bx by bw bh] (:bounds @drawable)
            cx (+ bx (/ bw 2))
            cy (+ by (/ bh 2))
            scale (/ bw (min width height))
            ex (.-clientX e)
            ey (.-clientY e)
            divx (- ex left (/ width 2))
            divy (- ey top (/ height 2))
            x (+ (* divx scale) cx)
            y (+ (* divy scale) cy)]
        (when (and @selected-id @mouse-down?)
          (let [k (if (string? @selected-id)
                    @selected-id
                    (pr-str (js->clj @selected-id)))]
            (when-let [idx (get (.-idxs simulation) k)]
              (when-let [particle (aget (.nodes simulation) idx)]
                (set! (.-fx particle) x)
                (set! (.-fy particle) y)
                (restart-simulation simulation)))))))}
   [draw-svg drawable simulation mouse-down? selected-id root editing]])

;; TODO: nodes should have a stronger charge than links
(defn create-simulation []
  (-> (js/d3.forceSimulation #js [])
      (.force "charge" (doto (js/d3.forceManyBody)
                         (.distanceMax 150)))
      (.force "link" (js/d3.forceLink #js []))))

(defn graph [nodes edges selected-id editing callbacks]
  (let [snapshot (reagent/atom {:bounds [400 400 600 600]})
        simulation (create-simulation)
        mouse-down? (reagent/atom nil)
        ;; TODO: mount/unmount
        watch (fn a-graph-watcher [k r a b]
                (when (not= a b)
                  (update-simulation simulation @nodes @edges)
                  (restart-simulation simulation)))]
    (update-simulation simulation @nodes @edges)
    (add-watch nodes :watch-nodes watch)
    (add-watch edges :watch-edges watch)
    (.on simulation "tick"
         (fn layout-tick []
           (swap! snapshot assoc
                  :nodes (js->clj (.nodes simulation) :keywordize-keys true)
                  :paths (.-paths simulation))
           (swap! snapshot update-bounds)))
    (fn graph-render [nodes edges selected-id editing callbacks]
      [draw-graph (reagent/current-component) snapshot simulation mouse-down? selected-id editing callbacks])))

(defcard-rg graph-example
  (fn []
    (let [nodes (reagent/atom [{:index 0 :db/id 0 :name "foo" :uid "zz"}
                               {:index 1 :db/id 1 :name "bar" :uid "zz"}])
          edges (reagent/atom [{:from 0 :to 1}])
          selected-id (reagent/atom nil)
          editing (reagent/atom nil)
          callbacks {}]
      (fn []
        [:div
         {:style {:border "1px solid black"}}
         [graph nodes edges selected-id editing callbacks]]))))
