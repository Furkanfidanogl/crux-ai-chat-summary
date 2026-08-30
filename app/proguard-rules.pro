# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Keep useful production crash traces while allowing class/member obfuscation.
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod

# If you keep the line number information, uncomment this to
# hide the original source file name.
-renamesourcefileattribute SourceFile

# Firestore reflection models. @Keep already protects them; these rules make the contract explicit.
-keep class com.furkanfidanoglu.cruxaisummarize.data.model.** {
    public <init>();
    public <fields>;
    public <methods>;
}

# XMLBeans relies on generated schema classes and reflection. R8 can safely remove the
# unused Apache POI desktop/AWT modules while retaining the DOCX/XLSX call graph.
-keep class org.apache.xmlbeans.** { *; }
-dontwarn javax.xml.stream.**
-dontwarn org.apache.xmlbeans.**

# Apache POI's optional desktop/SVG integrations are not used on Android.
-dontwarn java.awt.**
-dontwarn org.apache.batik.**
-dontwarn org.osgi.framework.**
