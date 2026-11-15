// What other Paper plugins compile against to read the play time XaliPlaytime keeps. The server provides it at run time, from the
// XaliPlaytime plugin, so a plugin that uses it lists XaliPlaytime as a dependency and does not bundle this.

plugins {
    `maven-publish`
}

dependencies {
    compileOnly(libs.paper.api)
}
