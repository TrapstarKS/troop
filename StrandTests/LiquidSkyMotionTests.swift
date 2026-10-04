import XCTest
@testable import Strand

/// Pins frame gating for Today's current starless sky.
/// Pure checks need no view, clock or simulator.
final class LiquidSkyMotionTests: XCTestCase {

    func testStarlessDaytimeHoursPauseTheLoop() {
        for light in [false, true] {
            for hour in [9.0, 12.0, 15.25, 17.5, 18.5, 19.0] {
                XCTAssertTrue(LiquidSky.pausesFrames(hour: hour, light: light, poseStill: false),
                              "hour \(hour), light \(light)")
            }
        }
    }

    func testDarkAppearanceHasNoStarsAndKeepsTheLoopPaused() {
        for minute in 0..<(24 * 60) {
            let hour = Double(minute) / 60
            XCTAssertEqual(liquidSkyAt(hour, light: false).stars, 0, "minute \(minute)")
            XCTAssertTrue(LiquidSky.pausesFrames(hour: hour, light: false, poseStill: false),
                          "minute \(minute)")
        }
    }

    /// White stars on the near-white light sky never move a pixel by `liquidStarMinLift` levels, so light appearance
    /// never runs the loop, at any minute.
    func testLightAppearanceNeverRunsTheLoop() {
        for minute in 0..<(24 * 60) {
            let hour = Double(minute) / 60
            XCTAssertLessThan(liquidStarPeakLift(hour: hour, light: true), liquidStarMinLift, "minute \(minute)")
            XCTAssertTrue(LiquidSky.pausesFrames(hour: hour, light: true, poseStill: false), "minute \(minute)")
        }
    }

    /// The frame gate remains consistent with the current dark keyframes, which have no drawable stars.
    func testDarkAppearanceStillFollowsTheDrawableStars() {
        for minute in 0..<(24 * 60) {
            let hour = Double(minute) / 60
            let drawable = liquidSkyHasVisibleStars(liquidSkyAt(hour, light: false).stars)
            XCTAssertEqual(LiquidSky.pausesFrames(hour: hour, light: false, poseStill: false), !drawable,
                           "minute \(minute)")
            if drawable {
                XCTAssertGreaterThanOrEqual(liquidStarPeakLift(hour: hour, light: false), liquidStarMinLift,
                                            "minute \(minute)")
            }
        }
    }

    /// Reduce Motion, Low Power Mode and the in-app toggle keep the loop paused at any hour.
    func testPoseStillPausesEvenUnderStars() {
        XCTAssertTrue(LiquidSky.pausesFrames(hour: 0, light: false, poseStill: true))
        XCTAssertTrue(LiquidSky.pausesFrames(hour: 23.5, light: true, poseStill: true))
    }

    /// Former dawn/dusk transition samples remain paused with the current zero-star keyframes.
    func testFormerDawnAndDuskSamplesKeepTheLoopPaused() {
        for hour in [7.05, 7.15, 19.1, 19.25] {
            XCTAssertTrue(LiquidSky.pausesFrames(hour: hour, light: false, poseStill: false), "hour \(hour)")
        }
    }

    /// At every minute of the day, in both appearances: while the loop is stopped no star, at any depth and at any
    /// point of its twinkle, both reaches the drawing floor and lifts a pixel by `liquidStarMinLift` levels, so
    /// stopping it never freezes a star someone could see; and while it runs, the nearest star at its peak does both.
    func testTheLoopStopsOnlyWhenNoStarCanBeDrawn() {
        let steps = (0...20).map { Double($0) / 20 }
        for minute in 0..<(24 * 60) {
            let hour = Double(minute) / 60
            for light in [false, true] {
                let stars = liquidSkyAt(hour, light: light).stars
                let lift = liquidStarPeakLift(hour: hour, light: light)
                if LiquidSky.pausesFrames(hour: hour, light: light, poseStill: false) {
                    if lift >= liquidStarMinLift {
                        for depth in steps {
                            for twinkle in steps {
                                XCTAssertLessThan(liquidStarOpacity(stars: stars, depth: depth, twinkle: twinkle),
                                                  liquidStarMinOpacity, "minute \(minute), light \(light)")
                            }
                        }
                    }
                } else {
                    XCTAssertGreaterThanOrEqual(liquidStarOpacity(stars: stars, depth: 1, twinkle: 1),
                                                liquidStarMinOpacity, "minute \(minute), light \(light)")
                    XCTAssertGreaterThanOrEqual(lift, liquidStarMinLift, "minute \(minute), light \(light)")
                }
            }
        }
    }
}
