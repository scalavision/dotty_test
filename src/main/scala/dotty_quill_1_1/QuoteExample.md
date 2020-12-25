# Dotty meta programming

In dotty you can use inline functions for meta programming:

```scala
inline def q: Quoted[Query[Person]] = quote {
  query[Person]
}

inline def onlyJoes: Quoted[Query[Person]] => Query[Person] = quote {
  (p: Query[Person]) => p.filter(p => p.name == "Joe")
}

run(onlyJoes(q))
```

  