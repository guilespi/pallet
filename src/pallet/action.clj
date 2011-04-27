(ns pallet.action
  "Actions implement the conversion of phase functions to script and other
   execution code.

   An action has a :action-type. Known types include :script/bash
   and :fn/clojure.

   An action has a :location, :origin for execution on the node running
   pallet, and :target for the target node.

   An action has an :execution, which is one of :aggregated, :in-sequence or
   :collected. Calls to :aggregated actions will be grouped, and run before
   :in-sequence actions. Calls to :collected actions will be grouped, and run
   after :in-sequence actions."
  {:author "Hugo Duncan"}
  (:require
   [pallet.action-plan :as action-plan]
   [pallet.argument :as argument]
   [pallet.session :as session]
   [clojure.contrib.condition :as condition]
   [clojure.contrib.def :as ccdef]
   [clojure.contrib.logging :as logging]
   [clojure.contrib.seq :as seq]
   [clojure.string :as string]))

;;; action defining functions

(defn schedule-action
  "Registers an action in the action plan. The action is generated by the
   specified action function and arguments that will be applied to the function
   when the action plan is executed.

   The action can be scheduled within one of three 'executions'
   (conceptually, sub-phases):

   :in-sequence - The generated action will be applied to the node
        \"in order\", as it is defined lexically in the source crate.
        This is the default.
   :aggregated - All aggregated actions are applied to the node
        in the order they are defined, but before all :in-sequence
        actions. Note that all of the arguments to any given
        action function are gathered such that there is only ever one
        invocation of each fn within each phase.
   :collected - All collected actions are applied to the node
        in the order they are defined, but after all :in-sequence
        action. Note that all of the arguments to any given
        action function are gathered such that there is only ever one
        invocation of each fn within each phase.

   The action-type determines how the action should be handled:

   :script/bash - action produces bash script for execution on remote machine
   :fn/clojure  - action is a function for local execution
   :transfer/to-local - action is a function specifying remote source
                        and local destination.
   :transfer/from-local - action is a function specifying local source
                          and remote destination."
  [session action-fn args execution action-type location]
  {:pre [session
         (keyword? (session/phase session))
         (keyword? (session/target-id session))]}
  (update-in
   session
   (action-plan/target-path session)
   action-plan/add-action
   (action-plan/action-map action-fn args execution action-type location)))

(defmacro action
  "Define an anonymous action"
  [execution action-type location [session & args] & body]
  (let [meta-map (when (and (map? (first body)) (> (count body) 1))
                   (first body))
        body (if meta-map (rest body) body)]
    `(let [f# (vary-meta (fn [~session ~@args] ~@body) merge ~meta-map)]
       (vary-meta
        (fn [& [session# ~@args :as argv#]]
          (schedule-action
           session# f# (rest argv#) ~execution ~action-type ~location))
        merge
        ~meta-map
        {::action-fn f#}))))

(defn action-fn
  "Retrieve the action-fn that is used to execute the specified action."
  [action]
  (::action-fn (meta action)))

;;; Convenience action definers for common cases
(defmacro bash-action
  "Define a remotely executed bash action function."
  [[session & args] & body]
  `(action :in-sequence :script/bash :target [~session ~@args] ~@body))

(defmacro clj-action
  "Define a clojure action to be executed on the origin machine."
  [[session & args] & body]
  `(action :in-sequence :fn/clojure :origin [~session ~@args] ~@body))

(defmacro aggregated-action
  "Define a remotely executed aggregated action function, which will
   be executed before :in-sequence actions."
  [[session & args] & body]
  `(action :aggregated :script/bash :target [~session ~@args] ~@body))

(defmacro collected-action
  "Define a remotely executed collected action function, which will
   be executed after :in-sequence actions."
  [[session & args] & body]
  `(action :collected :script/bash :target [~session ~@args] ~@body))

(defmacro as-clj-action
  "An adaptor for using a normal function as a local action function"
  ([f [session & args]]
     `(clj-action
       [~session ~@(map (comp symbol name) args)]
       (~f ~session ~@(map (comp symbol name) args))))
  ([f]
     `(as-clj-action
       ~f [~@(first (:arglists (meta (var-get (resolve f)))))])))

(defmacro def-action-def
  "Define a macro for definining action defining vars"
  [name actionfn1]
  `(defmacro ~name
     {:arglists '(~'[name [session & args] & body]
                  ~'[name [session & args] meta? & body])}
     [name# ~'& args#]
     (let [[name# args#] (ccdef/name-with-attributes name# args#)
           arglist# (first args#)
           body# (rest args#)
           meta-map# (when (and (map? (first body#)) (> (count body#) 1))
                       (first body#))
           name# (vary-meta
                  name#
                  #(merge
                    {:arglists (list 'quote (list arglist#))}
                    meta-map#
                    %))]
       `(def ~name# (~'~actionfn1 [~@arglist#] ~@body#)))))

(def-action-def def-bash-action pallet.action/bash-action)
(def-action-def def-clj-action pallet.action/clj-action)
(def-action-def def-aggregated-action pallet.action/aggregated-action)
(def-action-def def-collected-action pallet.action/collected-action)

(defn enter-scope
  "Enter a new action scope."
  [session]
  (update-in session (action-plan/target-path session) action-plan/push-block))

(defn leave-scope
  "Leave the current action scope."
  [session]
  (update-in session (action-plan/target-path session) action-plan/pop-block))