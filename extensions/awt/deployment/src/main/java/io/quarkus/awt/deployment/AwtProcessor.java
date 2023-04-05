package io.quarkus.awt.deployment;

import static io.quarkus.deployment.builditem.nativeimage.UnsupportedOSBuildItem.Os.WINDOWS;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.stream.Stream;

import io.quarkus.awt.runtime.graal.DarwinAwtFeature;
import io.quarkus.deployment.Feature;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.NativeImageFeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.JniRuntimeAccessBuildItem;
import io.quarkus.deployment.builditem.nativeimage.JniRuntimeAccessJSONBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourcePatternsBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeMinimalJavaVersionBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedPackageBuildItem;
import io.quarkus.deployment.builditem.nativeimage.UnsupportedOSBuildItem;
import io.quarkus.deployment.pkg.steps.NativeBuild;
import io.quarkus.deployment.pkg.steps.NativeOrNativeSourcesBuild;

class AwtProcessor {

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(Feature.AWT);
    }

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void nativeImageFeatures(BuildProducer<NativeImageFeatureBuildItem> nativeImageFeatures) {
        nativeImageFeatures.produce(new NativeImageFeatureBuildItem(DarwinAwtFeature.class));
    }

    @BuildStep(onlyIf = NativeBuild.class)
    UnsupportedOSBuildItem osSupportCheck() {
        return new UnsupportedOSBuildItem(WINDOWS,
                "Windows AWT integration is not ready in native-image and would result in " +
                        "java.lang.UnsatisfiedLinkError: no awt in java.library.path.");
    }

    @BuildStep(onlyIf = NativeBuild.class)
    NativeMinimalJavaVersionBuildItem nativeMinimalJavaVersionBuildItem() {
        return new NativeMinimalJavaVersionBuildItem(11, 13,
                "AWT: Some MLib related operations, such as filter in awt.image.ConvolveOp will not work. " +
                        "See https://bugs.openjdk.java.net/browse/JDK-8254024");
    }

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void resources(
            BuildProducer<NativeImageResourcePatternsBuildItem> resourcePatternsBuildItemBuildProducer) {
        resourcePatternsBuildItemBuildProducer
                .produce(NativeImageResourcePatternsBuildItem.builder()
                        .includePattern(".*/iio-plugin.*properties$") // Texts for e.g. exceptions strings
                        .includePattern(".*/.*pf$") // Default colour profiles
                        .build());
    }

    @BuildStep
    ReflectiveClassBuildItem setupReflectionClasses() {
        return ReflectiveClassBuildItem.builder(
                "com.sun.imageio.plugins.common.I18N",
                "sun.awt.X11.XToolkit",
                "sun.awt.X11FontManager",
                "sun.awt.X11GraphicsEnvironment").build();
    }

    @BuildStep
    ReflectiveClassBuildItem setupReflectionClassesWithMethods() {
        return ReflectiveClassBuildItem.builder(
                "javax.imageio.plugins.tiff.BaselineTIFFTagSet",
                "javax.imageio.plugins.tiff.ExifGPSTagSet",
                "javax.imageio.plugins.tiff.ExifInteroperabilityTagSet",
                "javax.imageio.plugins.tiff.ExifParentTIFFTagSet",
                "javax.imageio.plugins.tiff.ExifTIFFTagSet",
                "javax.imageio.plugins.tiff.FaxTIFFTagSet",
                "javax.imageio.plugins.tiff.GeoTIFFTagSet",
                "javax.imageio.plugins.tiff.TIFFTagSet",
                "sun.java2d.loops.OpaqueCopyAnyToArgb",
                "sun.java2d.loops.OpaqueCopyArgbToAny",
                "sun.java2d.loops.SetDrawLineANY",
                "sun.java2d.loops.SetDrawPathANY",
                "sun.java2d.loops.SetDrawPolygonsANY",
                "sun.java2d.loops.SetDrawRectANY",
                "sun.java2d.loops.SetFillPathANY",
                "sun.java2d.loops.SetFillRectANY",
                "sun.java2d.loops.SetFillSpansANY").methods().build();
    }

    /**
     * There are situations where we need more fine-grained control, e.g. as for:
     * https://github.com/openjdk/jdk17u-dev/blob/jdk-17.0.7+5/src/java.desktop/unix/native/libawt/awt/awt_LoadLibrary.c#L147
     * Json snippet directly injects raw records into already initialized JSON JNI config array.
     * To debug the result, see your built -runner jar:META-INF/native-image/jni-config.json
     * Use with caution.
     *
     * @return
     * @throws URISyntaxException
     * @throws IOException
     */
    @BuildStep
    JniRuntimeAccessJSONBuildItem setupAWTInit() throws URISyntaxException, IOException {
        final String jsonArray = Files.readString(Paths.get(getClass().getResource("/jni-json-snippet.json").toURI()));
        // Strip leading [ and trailing ] as we are injecting these elements into an already existing JSON array.
        return new JniRuntimeAccessJSONBuildItem(jsonArray.substring(1, jsonArray.length() - 1));
    }

    @BuildStep
    JniRuntimeAccessBuildItem setupJava2DClasses() {
        return new JniRuntimeAccessBuildItem(true, true, true,
                "com.sun.imageio.plugins.jpeg.JPEGImageReader",
                "com.sun.imageio.plugins.jpeg.JPEGImageWriter",
                "java.awt.AlphaComposite",
                "java.awt.Color",
                "java.awt.color.CMMException",
                "java.awt.color.ColorSpace",
                "java.awt.color.ICC_ColorSpace",
                "java.awt.color.ICC_Profile",
                "java.awt.color.ICC_ProfileGray",
                "java.awt.color.ICC_ProfileRGB",
                "java.awt.Composite",
                "java.awt.geom.AffineTransform",
                "java.awt.geom.GeneralPath",
                "java.awt.geom.Path2D",
                "java.awt.geom.Path2D$Float",
                "java.awt.geom.Point2D$Float",
                "java.awt.geom.Rectangle2D$Float",
                "java.awt.GraphicsEnvironment",
                "java.awt.image.AffineTransformOp",
                "java.awt.image.BandedSampleModel",
                "java.awt.image.BufferedImage",
                "java.awt.image.ColorModel",
                "java.awt.image.ComponentColorModel",
                "java.awt.image.ComponentSampleModel",
                "java.awt.image.ConvolveOp",
                "java.awt.image.DirectColorModel",
                "java.awt.image.IndexColorModel",
                "java.awt.image.Kernel",
                "java.awt.image.MultiPixelPackedSampleModel",
                "java.awt.image.PackedColorModel",
                "java.awt.image.PixelInterleavedSampleModel",
                "java.awt.image.Raster",
                "java.awt.image.SampleModel",
                "java.awt.image.SinglePixelPackedSampleModel",
                "java.awt.Rectangle",
                "java.awt.Transparency",
                "javax.imageio.IIOException",
                "javax.imageio.plugins.jpeg.JPEGHuffmanTable",
                "javax.imageio.plugins.jpeg.JPEGQTable",
                "sun.awt.image.BufImgSurfaceData",
                "sun.awt.image.BufImgSurfaceData$ICMColorData",
                "sun.awt.image.ByteBandedRaster",
                "sun.awt.image.ByteComponentRaster",
                "sun.awt.image.ByteInterleavedRaster",
                "sun.awt.image.BytePackedRaster",
                "sun.awt.image.DataBufferNative",
                "sun.awt.image.GifImageDecoder",
                "sun.awt.image.ImageRepresentation",
                "sun.awt.image.ImagingLib",
                "sun.awt.image.IntegerComponentRaster",
                "sun.awt.image.IntegerInterleavedRaster",
                "sun.awt.image.ShortBandedRaster",
                "sun.awt.image.ShortComponentRaster",
                "sun.awt.image.ShortInterleavedRaster",
                "sun.awt.image.SunWritableRaster",
                "sun.awt.image.WritableRasterNative",
                "sun.awt.SunHints",
                "sun.awt.X11FontManager",
                "sun.awt.X11GraphicsConfig",
                "sun.awt.X11GraphicsDevice",
                "sun.font.CharToGlyphMapper",
                "sun.font.Font2D",
                "sun.font.FontConfigManager",
                "sun.font.FontConfigManager$FcCompFont",
                "sun.font.FontConfigManager$FontConfigFont",
                "sun.font.FontConfigManager$FontConfigInfo",
                "sun.font.FontManagerNativeLibrary",
                "sun.font.FontStrike",
                "sun.font.FontUtilities",
                "sun.font.FreetypeFontScaler",
                "sun.font.GlyphLayout",
                "sun.font.GlyphLayout$EngineRecord",
                "sun.font.GlyphLayout$GVData",
                "sun.font.GlyphLayout$LayoutEngine",
                "sun.font.GlyphLayout$LayoutEngineFactory",
                "sun.font.GlyphLayout$LayoutEngineKey",
                "sun.font.GlyphLayout$SDCache",
                "sun.font.GlyphLayout$SDCache$SDKey",
                "sun.font.GlyphList",
                "sun.font.PhysicalStrike",
                "sun.font.StrikeMetrics",
                "sun.font.TrueTypeFont",
                "sun.font.Type1Font",
                "sun.java2d.cmm.lcms.LCMS",
                "sun.java2d.cmm.lcms.LCMSImageLayout",
                "sun.java2d.cmm.lcms.LCMSProfile",
                "sun.java2d.cmm.lcms.LCMSTransform",
                "sun.java2d.DefaultDisposerRecord",
                "sun.java2d.Disposer",
                "sun.java2d.InvalidPipeException",
                "sun.java2d.loops.Blit",
                "sun.java2d.loops.BlitBg",
                "sun.java2d.loops.CompositeType",
                "sun.java2d.loops.DrawGlyphList",
                "sun.java2d.loops.DrawGlyphListAA",
                "sun.java2d.loops.DrawGlyphListLCD",
                "sun.java2d.loops.DrawLine",
                "sun.java2d.loops.DrawParallelogram",
                "sun.java2d.loops.DrawPath",
                "sun.java2d.loops.DrawPolygons",
                "sun.java2d.loops.DrawRect",
                "sun.java2d.loops.FillParallelogram",
                "sun.java2d.loops.FillPath",
                "sun.java2d.loops.FillRect",
                "sun.java2d.loops.FillSpans",
                "sun.java2d.loops.GraphicsPrimitive",
                "sun.java2d.loops.GraphicsPrimitiveMgr",
                "sun.java2d.loops.MaskBlit",
                "sun.java2d.loops.MaskFill",
                "sun.java2d.loops.ScaledBlit",
                "sun.java2d.loops.SurfaceType",
                "sun.java2d.loops.TransformHelper",
                "sun.java2d.loops.XORComposite",
                "sun.java2d.NullSurfaceData",
                "sun.java2d.pipe.BufferedMaskBlit",
                "sun.java2d.pipe.GlyphListPipe",
                "sun.java2d.pipe.Region",
                "sun.java2d.pipe.RegionIterator",
                "sun.java2d.pipe.ShapeSpanIterator",
                "sun.java2d.pipe.SpanClipRenderer",
                "sun.java2d.pipe.SpanIterator",
                "sun.java2d.pipe.ValidatePipe",
                "sun.java2d.SunGraphics2D",
                "sun.java2d.SunGraphicsEnvironment",
                "sun.java2d.SurfaceData",
                "sun.java2d.xr.XRSurfaceData");
    }

    @BuildStep
    void runtimeInitializedClasses(BuildProducer<RuntimeInitializedPackageBuildItem> runtimeInitilizedPackages) {
        /*
         * Note that this initialization is not enough if user wants to deserialize actual images
         * (e.g. from XML). AWT Extension must be loaded for decoding JDK supported image formats.
         */
        Stream.of(
                "com.sun.imageio",
                "java.awt",
                "javax.imageio",
                "sun.awt",
                "sun.font",
                "sun.java2d")
                .map(RuntimeInitializedPackageBuildItem::new)
                .forEach(runtimeInitilizedPackages::produce);
    }
}
