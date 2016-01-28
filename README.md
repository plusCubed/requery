requery
=======

A micro ORM for Java/Android.

Defining entities:

```java
@Entity
abstract class AbstractPerson {

    @Key @Generated
    int id;

    @Index(name = "name_index")              // table specification
    String name;

    @OneToMany                               // relationships 1:1, 1:many, many to many
    Set<Phone> phoneNumbers;

    @Converter(EmailToStringConverter.class) // custom type conversion
    Email email;

    @PostLoad                                // lifecycle callbacks
    void afterLoad() {
        updatePeopleList();
    }

    // getter, setters, equals & hashCode automatically generated into Person.java
}

```
or from an interface:

```java
@Entity
public interface Person {

    @Key @Generated
    int getId();

    String getName();
    String getEmail();
    Date getBirthday();
    @OneToMany
    Result<Phone> getPhoneNumbers();

}
```

Queries:

```java
List<Person> query = data
    .select(Person.class)
    .where(Person.NAME.lower().like("b%"))
    .orderBy(Person.AGE.desc())
    .limit(5)
    .get().list();
```

Relationships: rather than collections such as sets, and lists which have to be materialized with
all the results, you can alternatively use query results directly: (sets and lists are supported to)

```java
@Entity
abstract class AbstractPerson {

    @Key @Generated
    int id;

    @ManyToMany
    Result<Group> groups;
    // equivalent to:
    // data.select(Group.class)
    // .join(Group_Person.class).on(Group_ID.equal(Group_Person.GROUP_ID))
    // .join(Person.class).on(Group_Person.PERSON_ID.equal(Person.ID))
    // .where(Person.ID.equal(id))
}
```

Java 8 streams:

```java
data.select(Person.class)
    .orderBy(Person.AGE.desc())
    .get()
    .stream().forEach(System.out::println);
```

RxJava Observables:

```java
Observable<Person> query = data
    .select(Person.class)
    .orderBy(Person.AGE.desc())
    .get()
    .toObservable();
```

RxJava observe query on table changes:

```java
Observable<Person> query = data
    .select(Person.class)
    .orderBy(Person.AGE.desc())
    .get()
    .toSelfObservable().subscribe(::updateFromResult);
```

Optional Read/write separation. If you prefer separating read from writes mark the entity as
@ReadOnly and use update statements to modify data instead.

```java
int rows = data.update(Person.class)
    .set(Person.ABOUT, "nothing")
    .set(Person.AGE, 50)
    .where(Person.AGE.equal(100)).get();
```

Features
--------

- No Reflection
- Fast startup
- Typed query language
- Table generation
- Supports JDBC and many popular databases
- Supports Android (SQLite, RecyclerView, Databinding)
- RxJava support
- Blocking and non-blocking API
- Partial objects/refresh
- Caching
- Lifecycle callbacks
- Custom type converters
- JPA annotations support (requery is not a JPA provider)

Reflection free
---------------

requery uses compile time annotation processing to generate your entity model classes. On Android
this means you get about the same performance reading objects from a query as if it was populated
using the standard Cursor and ContentValues API.

Type safe query
---------------

The compiled classes work with the query API to take advantage of compile time generated attributes.
Create type safe queries and avoid hard to maintain, error prone string concatenated queries.

Relationships
-------------

You can define One-to-One, One-to-Many, Many-to-One, and Many-to-Many relations in your models using
annotations. Relationships can be navigated in both directions. Of many type relations can be loaded
into standard java collection objects or into a more efficient iterable only object. Many-to-Many
junction tables can be generated automatically. Additionally the relation model is validated at
compile time eliminating runtime errors.

Android
-------

Designed specifically with Android support in mind.

Comparison to similar Android libraries:

Feature               |  requery |  ORMLite |  Squidb  |  DBFlow   | GreenDao
----------------------|----------|----------|----------|-----------|-----------
Relational mapping    |  Y       |  Y(1)    |  N       |  Y(1)     | Y(1)
Inverse relationships |  Y       |  N       |  N       |  N        | N
Compile time          |  Y       |  N       |  Y       |  Y        | Y(2)
JDBC Support          |  Y       |  Y       |  N       |  N        | N
query language        |  Y       |  N       |  Y(3)    |  Y(3)     | Y(3)
Table Generation      |  Y       |  Y       |  Y       |  Y        | Y
JPA annotations       |  Y       |  Y       |  N       |  N        | N

1) Excludes Many-to-Many
2) Not annotation based
3) Builder only

See requery-android/example for an example Android project using databinding and interface based
entities

Code generation
---------------

Generated entities from Abstract or Interface classes. Use JPA annotations or requery annotations.
Requery will generate getter/setters, equals() and hashcode().

Supported Databases
-------------------
Tested on some of the most popular databases:

- PostgresSQL (9.1+)
- MySQL 5.x
- Oracle 12c+
- Microsoft SQL Server 2012 or later
- SQLite (Android or with xerial JDBC driver)
- Apache Derby 10.11+
- H2 1.4+
- HSQLDB 2.3+

JPA Annotations
---------------

Only a subset of the JPA annotations are supported. These annotations are supported:

- Basic
- Cacheable
- Column
- Entity
- Enumerated
- GeneratedValue
- Id
- JoinColumn
- JoinTable
- ManyToMany
- ManyToOne
- OneToMany
- OneToOne
- PostLoad
- PostPersist
- PostRemove
- PostUpdate
- PrePersist
- PreRemove
- PreUpdate
- Table
- Transient
- Version

There is no support for embedded types or mapped superclasses. Unique/index constraints must
be placed on the field/method level. Advanced JPA features are not yet supported such as
mapping via @JoinTable to secondary tables to define relationships (outside of ManyToMany).

Using it
--------

Currently SNAPSHOT versions are available on http://oss.jfrog.org.

```gradle
repositories {
    jcenter()
    maven { url 'http://oss.jfrog.org/artifactory/oss-snapshot-local' }
}

dependencies {
    compile 'io.requery:requery:1.0-SNAPSHOT'
    compile 'io.requery:requery-android:1.0-SNAPSHOT' // for android
    apt 'io.requery:requery-processor:1.0-SNAPSHOT'   // prefer an APT plugin
}
```

Feedback and suggestions are welcome.

License
-------

    Copyright (C) 2016 requery.io

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

