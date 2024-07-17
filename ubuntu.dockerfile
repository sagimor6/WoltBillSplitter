

FROM ubuntu

ENV DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC

RUN apt-get update

RUN apt-get -y --no-install-recommends install openjdk-8-jdk gradle

WORKDIR /android
ENV ANDROID_SDK_ROOT=/android

RUN mkdir licenses
RUN echo 84831b9409646a918e30573bab4c9c91346d8abd >licenses/android-sdk-preview-license
RUN echo 24333f8a63b6825ea9c5514f83c2829b004d1fee >licenses/android-sdk-license

WORKDIR /temp_gradle

RUN (gradle init < /dev/null) && gradle wrapper && gradle --stop

RUN apt-get remove -y --purge --autoremove gradle

ADD gradle/wrapper/gradle-wrapper.properties gradle/wrapper/gradle-wrapper.properties

RUN GRADLE_VER=$(./gradlew -v -q | sed -rn 's/^Gradle\s+([0-9]+(\.[0-9]+)+)\s*$/\1/p') && \
	verlte() { [  "$1" = "$(/bin/echo -e "$1\n$2" | sort -V | head -n1)" ] ; } && \
	JDK_VER=8 && \
	if verlte 4.3 $GRADLE_VER; then JDK_VER=9; fi && \
	if verlte 4.7 $GRADLE_VER; then JDK_VER=10; fi && \
	if verlte 5.0 $GRADLE_VER; then JDK_VER=11; fi && \
	if verlte 5.4 $GRADLE_VER; then JDK_VER=12; fi && \
	if verlte 6.0 $GRADLE_VER; then JDK_VER=13; fi && \
	if verlte 6.3 $GRADLE_VER; then JDK_VER=14; fi && \
	if verlte 6.7 $GRADLE_VER; then JDK_VER=15; fi && \
	if verlte 7.0 $GRADLE_VER; then JDK_VER=16; fi && \
	if verlte 7.3 $GRADLE_VER; then JDK_VER=17; fi && \
	if verlte 7.5 $GRADLE_VER; then JDK_VER=18; fi && \
	if verlte 7.6 $GRADLE_VER; then JDK_VER=19; fi && \
	if verlte 8.3 $GRADLE_VER; then JDK_VER=20; fi && \
	if verlte 8.5 $GRADLE_VER; then JDK_VER=21; fi && \
	ANDROID_JDK_VERS="8 11 17" && \
	apt-get install -y --no-install-recommends $(for i in $ANDROID_JDK_VERS; do if [ $i -le $JDK_VER ]; then echo openjdk-${i}-jdk; fi; done)

RUN apt-get autoremove -y && apt-get clean && rm -rf /var/lib/apt/lists/*

#	JDK_VER=$(verlte 4.3 $GRADLE_VER && echo 9 || echo $JDK_VER) && \

RUN ./gradlew wrapper && ./gradlew --stop

WORKDIR /app

ADD . ./

RUN cp /temp_gradle/gradle/wrapper/gradle-wrapper.jar gradle/wrapper/
RUN cp /temp_gradle/gradlew gradlew

ARG USE_DUMMY_STORE_FILE
RUN if [ "$USE_DUMMY_STORE_FILE" = "yes" ]; then echo -e "storePassword=\nkeyPassword=\nkeyAlias=\nstoreFile=/dev/null\n" > keystore.properties; fi

RUN ./gradlew clean assembleRelease

ENTRYPOINT bash


