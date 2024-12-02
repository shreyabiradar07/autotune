create table IF NOT EXISTS kruize_authentication (id serial, authentication_type varchar(255), credentials jsonb, service_type varchar(255), primary key (id));
create table IF NOT EXISTS kruize_datasources (version varchar(255), name varchar(255), provider varchar(255), serviceName varchar(255), namespace varchar(255), url varchar(255), authentication_id serial, FOREIGN KEY (authentication_id) REFERENCES kruize_authentication(id), primary key (name));
create table IF NOT EXISTS kruize_dsmetadata (id serial, version varchar(255), datasource_name varchar(255), cluster_name varchar(255), namespace varchar(255), workload_type varchar(255), workload_name varchar(255), container_name varchar(255), container_image_name varchar(255), primary key (id));
alter table kruize_experiments add column experiment_type varchar(255), add column metadata_id bigint references kruize_dsmetadata(id), alter column datasource type varchar(255);
create table IF NOT EXISTS kruize_metric_profiles (api_version varchar(255), kind varchar(255), metadata jsonb, name varchar(255) not null, k8s_type varchar(255), profile_version float(53) not null, slo jsonb, primary key (name));
alter table kruize_recommendations add column experiment_type varchar(255);
create table IF NOT EXISTS kruize_metadata_profiles (api_version varchar(255), kind varchar(255), metadata jsonb, name varchar(255) not null, k8s_type varchar(255), profile_version float(53) not null, query_variables jsonb, primary key (name));
