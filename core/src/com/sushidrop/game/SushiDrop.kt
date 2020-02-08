package com.sushidrop.game

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Animation
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Array
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.utils.TimeUtils
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.graphics.g2d.Sprite
import com.badlogic.gdx.physics.box2d.*
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.physics.box2d.BodyDef
import com.badlogic.gdx.physics.box2d.PolygonShape


class SushiDrop : ApplicationAdapter() {
    // Basic things
    private lateinit var camera: OrthographicCamera
    private lateinit var batch: SpriteBatch

    private lateinit var hamtaroSheet: Texture
    private lateinit var sushiSheet: Texture
    private lateinit var treeBackground: Texture
    private lateinit var otherBackground: Texture

    private lateinit var sushiFrames: kotlin.Array<TextureRegion?>

    // Animations
    private var standingAnimation: Animation<TextureRegion>? = null // Must declare frame type (TextureRegion)
    private var walkRightAnimation: Animation<TextureRegion>? = null // Must declare frame type (TextureRegion)
    private var walkLeftAnimation: Animation<TextureRegion>? = null // Must declare frame type (TextureRegion)
    // A variable for tracking elapsed time for the animation
    var stateTime: Float = 0.toFloat()

    private lateinit var hamster: Rectangle
    private lateinit var touchPos: Vector3

    private lateinit var sushis: Array<Sushi>
    private var lastDropTime: Long = 0

    // Movement state
    private var currentDirection: HamtaroDirection = HamtaroDirection.STILL
    private var previousDirection: HamtaroDirection = HamtaroDirection.STILL
    private var walkRightStartTime: Long = 0
    private var walkLeftStartTime: Long = 0
    private var stillStartTime: Long = TimeUtils.millis()
    private var directionToAnimation: Map<HamtaroDirection, Animation<TextureRegion>?>? = null
    private val previousDiferences: MutableList<Float> = mutableListOf(0F)

    // World with gravity definition
    private var world = World(Vector2(0f, -75f), true)
    var body: Body? = null
    var debugRenderer: Box2DDebugRenderer? = null
    var debugMatrix: Matrix4? = null


    // CONSTANTS
    private val WIDTH = 480f
    private val HEIGHT = 800f
    private val FRAME_COLS = 7
    private val FRAME_ROWS = 4

    data class Sushi(var position: Rectangle, val spriteIndex: Int, val velocity: Int)

    enum class HamtaroDirection() {
        LEFT, RIGHT, STILL
    }

    private val WORLD_TO_BOX = 0.01f
    private val BOX_TO_WORLD = 100f

    override fun create() {
        Box2D.init()

        hamtaroSheet = Texture("hamtarosprite.png")
        sushiSheet = Texture("sushisprite.png")
        treeBackground = Texture("tree.png")
        otherBackground = Texture("background.png")
        camera = OrthographicCamera()
        camera.setToOrtho(false, WIDTH, HEIGHT)
        batch = SpriteBatch()
        touchPos = Vector3()
        sushis = Array<Sushi>()

        // place hamster
        hamster = Rectangle()
        hamster.width = 80f
        hamster.height = 80f
        hamster.x = WIDTH/2 - hamster.width/2
        hamster.y = 40f


        setUpSushiFrames()
        setUpHamtaroAnimations()

        var bodyDef = BodyDef()
        bodyDef.type = BodyDef.BodyType.DynamicBody

        // Set our body to the same position as our sprite
        bodyDef.position.set(hamster.x, hamster.y)

        // Create a body in the world using our definition
        body = world.createBody(bodyDef)
        debugRenderer = Box2DDebugRenderer()

        // Now define the dimensions of the physics shape
        var shape = PolygonShape()

        // We are a box, so this makes sense, no?
        // Basically set the physics polygon to a box with the same dimensions as our sprite
        shape.setAsBox(hamster.width/2, hamster.height/2)

        // FixtureDef is a confusing expression for physical properties
        // Basically this is where you, in addition to defining the shape of the body
        // you also define it's properties like density, restitution and others  we will see shortly
        // If you are wondering, density and area are used to calculate over all mass
        var fixtureDef = FixtureDef()
        fixtureDef.shape = shape
        fixtureDef.density = 1f
        fixtureDef.isSensor = true
        fixtureDef.restitution = 0.6f

//        val fixture = body.createFixture(fixtureDef);

        // Shape is the only disposable of the lot, so get rid of it
        shape.dispose()


        // Create the ground
        val groundBodyDef = BodyDef()
        groundBodyDef.type = BodyDef.BodyType.StaticBody
        groundBodyDef.position.set(Vector2(WIDTH/ 2f, 0f))
        // Create a body from the defintion and add it to the world
        val groundBody = world.createBody(groundBodyDef)

        // Create a polygon shape
        val groundBox = PolygonShape()
        // Set the polygon shape as a box which is twice the size of our view port and 20 high
        // (setAsBox takes half-width and half-height as arguments)
        groundBox.setAsBox(800f, 10.0f)
        // Create a fixture from our polygon shape and add it to our ground body
        groundBody.createFixture(groundBox, 0.0f)
        // Clean up after ourselves
        groundBox.dispose()


        spawnSushi()
    }

    override fun render() {
        // move world?
        world.step(Gdx.graphics.deltaTime, 7, 6)

        Gdx.gl.glClearColor(0f, .7f, .6f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        camera.update()
        val cameraCopy = camera.combined.cpy()
        debugRenderer!!.render(world, cameraCopy.scl(BOX_TO_WORLD))

        batch.projectionMatrix = camera.combined

        stateTime += Gdx.graphics.deltaTime // Accumulate elapsed animation time


        // Calculate hamster x movement
        var difference = 0f
        if (Gdx.input.isPeripheralAvailable(Input.Peripheral.Gyroscope)) {
            val gyroY = Gdx.input.gyroscopeY
            difference = gyroY * 5

            // average over the last 3
            if(previousDiferences.size > 3) {
                previousDiferences.removeAt(0)
            }
            previousDiferences.add(difference)

            difference = previousDiferences.sum() / previousDiferences.size
            // hamo speed limits
            val speedLimit = 14f
            if (difference > speedLimit) difference = speedLimit
            if (difference < -1 * speedLimit) difference =  -1 * speedLimit

            hamster.x += difference
        }

        // calculate hamster y movement
//        if(Gdx.input.isTouched && hamster.y < 40) {
//            body!!.applyLinearImpulse(Vector2(0f, 10f), body!!.worldCenter, true)
//        }
//        hamster.y = body!!.position.y
//        Gdx.app.log("y pos", "hello   ${hamster.y}")



        // keep hamo in bounds
        if (hamster.x < 0) hamster.x = 0f
        if (hamster.x > 360) hamster.x = 360f


        val currentHamsterFrame = getHamsterFrame(difference)


        // ==== Drawing =====//
        batch.begin()
        batch.draw(treeBackground,0f,0f);
        batch.draw(currentHamsterFrame, hamster.x, hamster.y)
        for (sushi in sushis) {
            batch.draw(sushiFrames[sushi.spriteIndex], sushi.position.x, sushi.position.y)
        }
        batch.end()
        // == end drawing //

        // === Sushi Logic ? === /
        if(TimeUtils.nanoTime() - lastDropTime > 1000000000) {
            spawnSushi()
        }

        val iter = sushis.iterator()
        while (iter.hasNext()) {
            val sushi = iter.next()
            sushi.position.y -= (160 + sushi.velocity * 20 + stateTime) * Gdx.graphics.deltaTime
            if (sushi.position.y < 25 || sushi.position.overlaps(hamster)) {
                iter.remove()
            }
        }


        // Scale down the sprite batches projection matrix to box2D size
//        debugMatrix = batch.projectionMatrix.cpy().scale(64f,
//                64f, 0f);

//        debugRenderer.render(world, debugMatrix)
    }

    override fun dispose() {
        world.dispose()
        hamtaroSheet.dispose()
        sushiSheet.dispose()
        batch.dispose()
    }

    /**
     * Private wheee
     */

    private fun setUpHamtaroAnimations() {
        val temp = TextureRegion(hamtaroSheet, 27, 8, 789, 500)
                .split(789 / FRAME_COLS, 500 / FRAME_ROWS)

        // Place the regions into a 1D array in the correct order, starting from the top
        // left, going across first. The Animation constructor requires a 1D array.
        val hamsterframe = arrayOfNulls<TextureRegion>(FRAME_COLS * FRAME_ROWS)
        var index = 0
        for (i in 0 until FRAME_ROWS) {
            for (j in 0 until FRAME_COLS) {
                hamsterframe[index++] = temp[i][j]
            }
        }

        val standingFrames = hamsterframe.sliceArray(IntRange(5,6))
        val walkRightFrames = hamsterframe.sliceArray(IntRange(15,18))
        val walkLeftFrames = hamsterframe.sliceArray(IntRange(19,23))

        // Initialize the Animation with the frame interval and array of frames
        standingAnimation = Animation<TextureRegion>(0.35f, *standingFrames)
        walkRightAnimation = Animation<TextureRegion>(0.07f, *walkRightFrames)
        walkLeftAnimation = Animation<TextureRegion>(0.07f, *walkLeftFrames)
    }

    private fun setUpSushiFrames() {
        val temp = TextureRegion.split(sushiSheet, 66, 66)
        sushiFrames =  arrayOfNulls(9  * 15)
        var index = 0
        for (i in 0 until 9) {
            for (j in 0 until 15) {
                sushiFrames[index++] = temp[i][j]
            }
        }
    }

    private fun spawnSushi() {

        val position = Rectangle()
        position.x = MathUtils.random(30f, WIDTH - 94)
        position.y = HEIGHT
        position.width = 40f
        position.height = 40f

        sushis.add(Sushi(position, MathUtils.random(0, 9 * 15 - 1), MathUtils.random(0,4)))
        lastDropTime = TimeUtils.nanoTime()
    }

    private fun getHamsterFrame(difference: Float) : TextureRegion? {
        // Thresholds for
        val smallThreshold = 1
        if (difference > smallThreshold &&  currentDirection != HamtaroDirection.RIGHT) {
            walkRightStartTime = TimeUtils.millis()
            currentDirection = HamtaroDirection.RIGHT
//            Gdx.app.log("walk", "right  $difference")
        } else if (difference < -1 * smallThreshold && currentDirection != HamtaroDirection.LEFT){
            walkLeftStartTime = TimeUtils.millis()
            previousDirection = currentDirection
            currentDirection = HamtaroDirection.LEFT
//            Gdx.app.log("walk", "left  $difference")
        } else if (difference < .5  && difference > -.5
                && currentDirection != HamtaroDirection.STILL) {
            stillStartTime = TimeUtils.millis()
            previousDirection = currentDirection
            currentDirection = HamtaroDirection.STILL
//            Gdx.app.log("walk", "still  $difference")
        }


        var currentFrame = standingAnimation!!.getKeyFrame(stateTime, true)
        if (currentDirection == HamtaroDirection.RIGHT &&
                (difference * TimeUtils.timeSinceMillis(walkRightStartTime) > 900)) {
             currentFrame = walkRightAnimation!!.getKeyFrame(stateTime, true)
        } else if (currentDirection == HamtaroDirection.LEFT &&
                (difference * TimeUtils.timeSinceMillis(walkLeftStartTime) < -900)) {
            currentFrame = walkLeftAnimation!!.getKeyFrame(stateTime, true)
        }

        return currentFrame
    }
}
