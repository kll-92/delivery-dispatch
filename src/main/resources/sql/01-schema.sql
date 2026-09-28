create table rider(
    id BIGINT not null auto_increment,
    rider_no varchar(32) not null ,
    name varchar(64) not null,
    phone varchar(20) not null,
    status tinyint not null default 0,
    longitude decimal(10,7),
    latitude decimal(10,7),
    current_order_count int not null default 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    primary key (id),
    unique key uk_rider_no(rider_no),
    unique key uk_rider_phone(phone),
    key idx_rider_status_order_count (status,current_order_count)
);


create table delivery_order(
    id BIGINT not null auto_increment,
    order_no varchar(32) not null,
    merchant_name varchar(64) not null,
    merchant_phone varchar(20) not null,
    merchant_address varchar(255) not null,
    merchant_longitude decimal(10,7) not null,
    merchant_latitude decimal(10,7) not null,
    receiver_name varchar(64) not null,
    receiver_phone varchar(20) not null,
    receiver_address varchar(255) not null,
    receiver_longitude decimal(10,7) not null,
    receiver_latitude decimal(10,7) not null,
    rider_id bigint,
    status tinyint not null default 0,
    delivery_fee decimal(10,2) not null default 0.00,
    created_at datetime not null default current_timestamp,
    updated_at datetime not null default current_timestamp on update current_timestamp,

    primary key (id),
    unique key uk_order_no (order_no),
    key idx_order_status_created_at (status, created_at),
    key idx_order_rider_status (rider_id, status)
);
