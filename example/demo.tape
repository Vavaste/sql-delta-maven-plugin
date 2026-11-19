# Records ../docs/demo.gif, the demo at the top of the main README, with VHS (https://github.com/charmbracelet/vhs).
# Needs VHS (brew install vhs) and Docker. Run it from this folder: vhs demo.tape
# It starts the database, applies V1, records, then removes the habitat field, V2 and the database.

Output ../docs/demo.gif

Set Shell "bash"
Set FontSize 18
Set Width 1200
Set Height 560
Set Padding 24
Set Framerate 10
Set TypingSpeed 35ms
Set Theme "Catppuccin Mocha"
Set WaitTimeout 3m

Hide
# A symlink keeps the printed paths short and free of where the repository is checked out.
Type `ln -sfn "$(cd .. && pwd)" /tmp/sql-delta-maven-plugin && cd /tmp/sql-delta-maven-plugin/example` Enter
# -q hides Maven's own output; the plugin's logger stays at info.
Type `export GIT_PAGER=cat MAVEN_OPTS=-Dorg.slf4j.simpleLogger.log.io.github.vavaste=info MAVEN_ARGS="$MAVEN_ARGS -f $PWD/pom.xml"` Enter
Type "docker compose up -d --wait 2>/dev/null && ../mvnw -q spring-boot:run >/dev/null" Enter
Wait
Type `perl -0pi -e 's/(\tprivate String species;\n)/$1\n\tprivate String habitat;\n/' src/main/java/com/example/zoo/Animal.java` Enter
Type "clear" Enter
Sleep 500ms
Show

Type "git diff ." Sleep 300ms Enter
Wait
Sleep 3s

Type "../mvnw -q sql-delta:diff" Sleep 300ms Enter
Wait
Sleep 4s

Hide
Type "clear" Enter
Sleep 300ms
Show

Type "../mvnw -q sql-delta:diff -Dsqldelta.name=add_animal_habitat" Sleep 300ms Enter
Wait
Sleep 2s

Type "cat src/main/resources/db/migration/V2__add_animal_habitat.sql" Sleep 300ms Enter
Wait
Sleep 6s

Hide
Type `perl -0pi -e 's/\n\tprivate String habitat;\n//' src/main/java/com/example/zoo/Animal.java` Enter
Type "rm src/main/resources/db/migration/V2__add_animal_habitat.sql && ../mvnw -q clean" Enter
Wait
Type "cd - >/dev/null && rm /tmp/sql-delta-maven-plugin && docker compose down -v 2>/dev/null" Enter
Wait
