(ns metabase.test.data.oracle
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.set :as set]
            [clojure.string :as str]
            [honeysql.format :as hformat]
            [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
            [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
            [metabase.test.data.interface :as tx]
            [metabase.test.data.sql :as sql.tx]
            [metabase.test.data.sql-jdbc :as sql-jdbc.tx]
            [metabase.test.data.sql-jdbc.execute :as execute]
            [metabase.test.data.sql-jdbc.load-data :as load-data]
            [metabase.test.data.sql.ddl :as ddl]
            [metabase.util :as u]))

(sql-jdbc.tx/add-test-extensions! :oracle)

;; Similar to SQL Server, Oracle on AWS doesn't let you create different databases;
;; We'll create a unique schema (the same as a "User" in Oracle-land) for each test run and use that to keep
;; tests from clobbering over one another; we'll also qualify the names of tables to include their DB name
;;
;; e.g.
;; H2 Tests                   | Oracle Tests
;; ---------------------------+------------------------------------------------
;; PUBLIC.VENUES.ID           | CAM_195.test_data_venues.id
;; PUBLIC.CHECKINS.USER_ID    | CAM_195.test_data_checkins.user_id
;; PUBLIC.INCIDENTS.TIMESTAMP | CAM_195.sad_toucan_incidents.timestamp
(defonce ^:private session-schema-number (rand-int 200))
(defonce           session-schema        (str "CAM_" session-schema-number))
(defonce ^:private session-password      (apply str (repeatedly 16 #(rand-nth (map char (range (int \a) (inc (int \z))))))))
;; Session password is only used when creating session user, not anywhere else

(def ^:private connection-details
  (delay
   {:host     (tx/db-test-env-var-or-throw :oracle :host)
    :port     (Integer/parseInt (tx/db-test-env-var-or-throw :oracle :port "1521"))
    :user     (tx/db-test-env-var-or-throw :oracle :user)
    :password (tx/db-test-env-var-or-throw :oracle :password)
    :sid      (tx/db-test-env-var-or-throw :oracle :sid)
    :ssl      (tx/db-test-env-var :oracle :ssl false)}))

(defmethod tx/dbdef->connection-details :oracle [& _] @connection-details)

(defmethod tx/sorts-nil-first? :oracle [_ _] false)

(doseq [[base-type sql-type] {:type/BigInteger             "NUMBER(*,0)"
                              :type/Boolean                "NUMBER(1)"
                              :type/Date                   "DATE"
                              :type/Temporal               "TIMESTAMP"
                              :type/DateTime               "TIMESTAMP"
                              :type/DateTimeWithTZ         "TIMESTAMP WITH TIME ZONE"
                              :type/DateTimeWithLocalTZ    "TIMESTAMP WITH LOCAL TIME ZONE"
                              :type/DateTimeWithZoneOffset "TIMESTAMP WITH TIME ZONE"
                              :type/DateTimeWithZoneID     "TIMESTAMP WITH TIME ZONE"
                              :type/Decimal                "DECIMAL"
                              :type/Float                  "BINARY_FLOAT"
                              :type/Integer                "INTEGER"
                              :type/Text                   "VARCHAR2(4000)"}]
  (defmethod sql.tx/field-base-type->sql-type [:oracle base-type] [_ _] sql-type))

;; If someone tries to run Time column tests with Oracle give them a heads up that Oracle does not support it
(defmethod sql.tx/field-base-type->sql-type [:oracle :type/Time]
  [_ _]
  (throw (UnsupportedOperationException. "Oracle does not have a TIME data type.")))

(defmethod sql.tx/drop-table-if-exists-sql :oracle
  [_ {:keys [database-name]} {:keys [table-name]}]
  ;; ⅋ replaced with `;` in the actual executed SQL; `;` itself is automatically removed Missing IN or OUT parameter
  (format "BEGIN
             EXECUTE IMMEDIATE 'DROP TABLE \"%s\".\"%s\" CASCADE CONSTRAINTS'⅋
           EXCEPTION
             WHEN OTHERS THEN
               IF SQLCODE != -942 THEN
                 RAISE⅋
               END IF⅋
           END⅋"
          session-schema
          (tx/db-qualified-table-name database-name table-name)))

(defmethod sql.tx/create-db-sql :oracle [& _] nil)

(defmethod sql.tx/drop-db-if-exists-sql :oracle [& _] nil)

(defmethod execute/execute-sql! :oracle [& args]
  (apply execute/sequentially-execute-sql! args))

(defmethod sql.tx/pk-sql-type :oracle [_]
  "INTEGER GENERATED BY DEFAULT AS IDENTITY (START WITH 1 INCREMENT BY 1) NOT NULL")

(defmethod sql.tx/qualified-name-components :oracle [& args]
  (apply tx/single-db-qualified-name-components session-schema args))

(defmethod tx/id-field-type :oracle [_] :type/Decimal)

(defmethod load-data/load-data! :oracle
  [driver dbdef tabledef]
  (load-data/load-data-add-ids-chunked! driver dbdef tabledef))

(defmethod tx/has-questionable-timezone-support? :oracle [_] true)

;; Oracle has weird syntax for inserting multiple rows, it looks like
;;
;; INSERT ALL
;;     INTO table (col1,col2) VALUES (val1,val2)
;;     INTO table (col1,col2) VALUES (val1,val2)
;; SELECT * FROM dual;
;;
;; So this custom HoneySQL type below generates the correct DDL statement
(defmethod ddl/insert-rows-honeysql-form :oracle
  [driver table-identifier row-or-rows]
  (reify hformat/ToSql
    (to-sql [_]
      (format
       "INSERT ALL %s SELECT * FROM dual"
       (str/join
        " "
        (for [row  (u/one-or-many row-or-rows)
              :let [columns (keys row)]]
          (str/replace
           (hformat/to-sql
            ((get-method ddl/insert-rows-honeysql-form :sql/test-extensions) driver table-identifier row))
           #"INSERT INTO"
           "INTO")))))))

(defn- dbspec [& _]
  (sql-jdbc.conn/connection-details->spec :oracle @connection-details))

(defn- non-session-schemas
  "Return a set of the names of schemas (users) that are not meant for use in this test session (i.e., ones that should
  be ignored). (This is used as part of the implementation of `excluded-schemas` for the Oracle driver during tests.)"
  []
  (set (map :username (jdbc/query (dbspec) ["SELECT username FROM dba_users WHERE username <> ?" session-schema]))))

(defonce ^:private original-excluded-schemas
  (get-method sql-jdbc.sync/excluded-schemas :oracle))

(defmethod sql-jdbc.sync/excluded-schemas :oracle
  [driver]
  (set/union
   (original-excluded-schemas driver)
   ;; This is similar hack we do for Redshift, see the explanation there we just want to ignore all the test
   ;; "session schemas" that don't match the current test
   (non-session-schemas)))


;;; Clear out the session schema before and after tests run
;; TL;DR Oracle schema == Oracle user. Create new user for session-schema
(defn- execute! [format-string & args]
  (let [sql (apply format format-string args)]
    (println (u/format-color 'blue "[oracle] %s" sql))
    (jdbc/execute! (dbspec) sql))
  (println (u/format-color 'blue "[ok]")))

(defn create-user!
  ;; default to using session-password for all users created this session
  ([username]
   (create-user! username session-password))
  ([username password]
   (execute! "CREATE USER \"%s\" IDENTIFIED BY \"%s\" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS"
             username
             password)))

(defn drop-user! [username]
  (u/ignore-exceptions
   (execute! "DROP USER %s CASCADE" username)))

(defmethod tx/before-run :oracle
  [_]
  (drop-user! session-schema)
  (create-user! session-schema))

(defmethod tx/aggregate-column-info :oracle
  ([driver ag-type]
   (merge
    ((get-method tx/aggregate-column-info ::tx/test-extensions) driver ag-type)
    (when (#{:count :cum-count} ag-type)
      {:base_type :type/Decimal})))

  ([driver ag-type field]
   (merge
    ((get-method tx/aggregate-column-info ::tx/test-extensions) driver ag-type field)
    (when (#{:count :cum-count} ag-type)
      {:base_type :type/Decimal}))))
