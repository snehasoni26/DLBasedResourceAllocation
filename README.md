# AutoscalingExample (CloudSim Plus)

This project demonstrates a predictive autoscaling simulation using CloudSim Plus.

## How to Build and Run

1. **Install Java (JDK 8+) and Maven**
2. From the project root, run:

```bash
mvn dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dependency
mkdir -p target/single-classes
javac -cp "target/dependency/*" -d target/single-classes src/main/java/org/cloudsimplus/examples/autoscaling/AutoscalingExample.java
java -cp "target/single-classes:target/dependency/*" org.cloudsimplus.examples.autoscaling.AutoscalingExample
```

## Project Structure
- `src/main/java/org/cloudsimplus/examples/autoscaling/AutoscalingExample.java` — Main simulation source
- `pom.xml` — Maven dependencies
- `target/` — Build output

## Notes
- Only the autoscaling example is included for minimal setup.
- For more CloudSim Plus examples, see the [CloudSim Plus documentation](https://cloudsimplus.org/).
