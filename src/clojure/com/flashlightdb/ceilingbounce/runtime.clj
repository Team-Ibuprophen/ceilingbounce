(ns com.flashlightdb.ceilingbounce.runtime
    (:require [neko.activity :refer [defactivity set-content-view!]]
              [neko.notify :as notify]
              [neko.resource :as res]
              [neko.find-view :refer [find-view]]
              [neko.threading :refer [on-ui]]
              [neko.log :as log]
              [neko.ui :as ui]
              [clojure.java.io :as io]
              [clojure.core.async
               :as a
               :refer [>! <! >!! <!! go chan buffer close! thread
                       alts! alts!! timeout]]
              [com.flashlightdb.ceilingbounce.csv :as csv]
              [com.flashlightdb.ceilingbounce.common :as common
               :refer [identity*
                       config
                       main-activity
                       do-nothing
                       update-ui
                       read-field
                       update-main]]
              [amalloy.ring-buffer :refer [ring-buffer]]
              )
    (:import android.widget.EditText
             [android.app
              Activity]
             [android.graphics
              Color
              Bitmap
              Canvas
              Bitmap$Config
              Bitmap$CompressFormat]
             java.io.File
             neko.App)
    (:use overtone.at-at
          com.flashlightdb.ceilingbounce.graph))

(def peak-lux (atom 0))
(def test-time (atom 0))
(def runtime-pool (mk-pool))
(def output-file-name (atom "Name output file"))

(defn handle-lux [lux]
  (update-main ::lux-now
               :text (str lux))
  (swap! peak-lux max lux))

(defn handle-peak [lux]
  (update-main ::lux-peak
               :text (str lux)))

(defn reset-peak [_evt]
  (common/reset-peak)
  (swap! peak-lux min 0))

(declare runtime-test)

(def output (atom (ring-buffer 1000)))

(defn nanos-since [start-time]
  (-> (. System nanoTime)
      (- start-time)))

(defn minutes-since [start-time]
  (float (/ (nanos-since start-time) 60000000000)))

#_(defn handle-lux-rt [lux start-time]
  (let [offset (minutes-since start-time)]
    (swap! output conj [lux offset])))

(defn sample-lux []
  (swap! output conj [@common/lux (minutes-since @test-time)]))

(def lux-30s (atom 0))

(defn percent [lux]
  (if lux
    (float (* 100 (/ lux (max 1 @lux-30s))))
    0))

(defn write-line [lux minutes csv-path]
  (csv/write-csv-line [lux minutes (percent lux)]
                      csv-path))

(defn get-dir-name []
  (let [dirname (read-field @main-activity ::filename)]
    (if (empty? dirname)
      "test"
      dirname)))

(defn get-dir-path []
  (str common/storage-dir (get-dir-name) "/"))

(defn path [start-time ext]
  (let [dirname (get-dir-name)]
    (str common/storage-dir dirname "/" dirname "-" start-time "." ext)))

(def graph-props
  {:AxisTitleTextSize 16
   :ChartTitleTextSize 20
   :LabelsTextSize 16
   :XTitle "Minutes"
   :YTitle "Relative Output"
   :ShowLegend false
   :ZoomEnabled false
   :YAxisMin 0
   :ApplyBackgroundColor true
   :BackgroundColor Color/BLACK
   :YLabels 10
   :XLabels 10})

(def line-props
  {:Color Color/YELLOW
   :LineWidth 2.0})

(declare live-chart)

(defn setup-chart []
  ; This has to be a function or (XyMultipleSeriesDataset.) fails at compile with
  ; java.lang.ExceptionInInitializerError

  (defonce live-chart
    (let [series (make-series "Runtime")
          dataset (make-dataset series)
          srenderer (make-series-renderer line-props)
          mrenderer (make-multi-renderer graph-props srenderer)
          view (make-view @main-activity dataset mrenderer)]
      (->LineGraph
       series
       srenderer
       mrenderer
       dataset
       view))))


(defn add-reading [minutes lux]
  (add-point live-chart
             minutes
             (percent lux)))

(defn save-chart [chart-path]
  ; While the chart view should have a .toBitmap method, it returns nil
  ; probably because the larger chart is not drawn, so we draw it manually
  (let [bitmap (Bitmap/createBitmap 1400 1050 Bitmap$Config/ARGB_8888)
        canvas (Canvas. bitmap)
        srenderer (make-series-renderer line-props)
        mrenderer (make-multi-renderer graph-props srenderer)
        view (make-view @main-activity (:dataset live-chart) mrenderer)]
    (call-setter mrenderer
                 :ChartTitle 
                 (read-field @main-activity ::filename))
    (on-ui (doto view
             (.layout 0 0 1400 1050)
             (.draw canvas)
             .zoomReset)
           ; writing the bitmap has to happen after the drawing stuff is done
           ; but let's not block the UI thread
           (future (with-open [o (io/output-stream chart-path)]
                     (-> bitmap
                         (.compress Bitmap$CompressFormat/PNG 90 o)))))))

(defn handle-output [output-vec dir-path csv-path]
  (let [pair (last output-vec)
        lux (first pair)
        minutes (second pair)]
    (when (and minutes ; NPE
               (>= minutes 0.5))      
      (when-not (.exists (io/as-file dir-path))
        (.mkdirs (io/as-file dir-path)))
      (when-not (.exists (io/as-file csv-path))
        (swap! lux-30s identity* lux)
        (clear-chart live-chart)
        (future (doseq [p @output]
                  (add-reading (second p) (first p))))
        (future (doseq [p @output]
                  (write-line (first p) (second p) csv-path))))
      (write-line lux minutes csv-path))
    (when (and minutes lux)
      (add-reading minutes lux))
    (when (and minutes ; slow down the sample rate so the graph doesn't hang
               (> minutes 100)
               (< minutes 100.1))
      (stop-and-reset-pool! runtime-pool)
      (every 10000
             sample-lux
             runtime-pool
             :desc "Sample the light meter reading"))))

(defn stop-runtime-test [_evt]
  (remove-watch common/lux :runtime-watch)
  (future (save-chart (path @test-time "png")))
  (swap! output identity* [])
  (stop-and-reset-pool! runtime-pool)
  (update-main ::runtime-test
             :text "Start runtime test"
             :on-click #'runtime-test))

(defn runtime-test [_evt]
  (.mkdirs (io/as-file common/storage-dir))
  (let [start-time (. System nanoTime)
        dir-path (get-dir-path)
        csv-path (path start-time "csv")]
    (swap! test-time max start-time)
    (swap! lux-30s identity* @common/lux) ; start the graph with this, update later
    (clear-chart live-chart)
    (call-setter (:multi-renderer live-chart)
                 :ChartTitle 
                 (read-field @main-activity ::filename))
    #_(add-watch common/lux :runtime-watch
               (fn [_key _ref _old new]
                 (handle-lux-rt new start-time)))
    (every 1000
           sample-lux
           runtime-pool
           :desc "Sample the light meter reading")
    (add-watch output :runtime-watch
               (fn [_key _ref _old new]
                 (handle-output new dir-path csv-path)))
    (update-main ::runtime-test
            :text "Stop test"
            :on-click #'stop-runtime-test)))

(declare runtime-view)

(def runtime-layout [:linear-layout (merge common/linear-layout-opts
                                           {:id ::runtime})
                     [:edit-text {:id ::filename
                                  :hint "Name output file"
                                  :text @output-file-name
                                  :layout-width :fill}]
                     [:button {:id ::runtime-test
                               :text "Start runtime test"
                               :on-click #'runtime-test}]
                     [:text-view {:id ::lux-now
                                  :text-size [48 :dip]}]
                     [:relative-layout {:layout-width :fill
                                        :layout-height :wrap
                                        :layout-gravity 1}
                      [:text-view {:text "Peak: "
                                   :id ::peak-label}]
                      [:text-view {:id ::lux-peak
                                   :layout-to-right-of ::peak-label
                                   :text "0"}]
                      [:button {:id ::reset-button
                                :text "Reset peak"
                                :layout-below ::lux-peak
                                :on-click #'reset-peak}]]
                     [:linear-layout (merge common/linear-layout-opts
                                            {:id ::chart
                                             :layout-gravity 3
                                             :layout-height :fill})]
                     ])

(defn activate-chart []
  (on-ui (try (-> (find-view @main-activity ::chart)
                  (.addView (:view live-chart)))
              (catch Exception e nil))))

(defn deactivate-chart []
  (on-ui (try (-> (:view live-chart)
                  .getParent
                  (.removeView (:view live-chart)))
              (catch Exception e nil))))

(defn activate-tab [& _args]
  (on-ui
   (set-content-view! @main-activity
                      runtime-layout))
  (setup-chart)
  (deactivate-chart)
  (activate-chart)
  (add-watch common/lux
             :lux-instant-runtime
             (fn [_key _ref _old new]
               (handle-lux new)))
  (add-watch peak-lux
             :lux-peak-runtime
             (fn [_key _ref _old new]
               (handle-peak new))))

(defn deactivate-tab [& _args]
  (swap! output-file-name identity* (read-field @main-activity ::filename))
  (remove-watch common/lux :lux-instant-runtime)
  (remove-watch peak-lux :lux-peak-runtime)
  (common/set-30s nil)
  (deactivate-chart))

#_(on-ui (.invalidate (find-view @main-activity ::runtime)))
