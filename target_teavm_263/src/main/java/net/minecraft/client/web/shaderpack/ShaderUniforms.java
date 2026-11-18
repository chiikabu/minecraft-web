// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.shaderpack;

import com.mojang.renderpearl.backend.opengl.GlApi;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

public final class ShaderUniforms {
	private final Map<String, float[]> values = new HashMap<>();
	private final List<Object[]> customs = new ArrayList<>();
	private final PackExpression.Context context = new PackExpression.Context();
	private final float sunPathRotation;
	private final float shadowDistance;
	private final float shadowInterval;
	private final float eyeBrightnessHalflife;
	private final float centerDepthHalflife;
	private final float wetnessHalflife;
	private final float drynessHalflife;

	private final Matrix4f prevModelView = new Matrix4f();
	private final Matrix4f prevProjection = new Matrix4f();
	private float[] prevCamera = {0, 0, 0};
	private boolean first = true;
	private long lastNanos;
	private double timeCounter;
	private int frameCounter;
	private float eyeSkySmooth = -1;
	private float eyeBlockSmooth;
	private float wetness;
	private float centerDepth = 1.0F;

	public ShaderUniforms(final PackPrograms programs) {
		ShaderPack.Directives d = programs.directives;
		this.sunPathRotation = d.constFloat("sunPathRotation", 0.0F);
		this.shadowDistance = d.constFloat("shadowDistance", 160.0F);
		this.shadowInterval = d.constFloat("shadowIntervalSize", 2.0F);
		this.eyeBrightnessHalflife = d.constFloat("eyeBrightnessHalflife", 10.0F);
		this.centerDepthHalflife = d.constFloat("centerDepthHalflife", 1.0F);
		this.wetnessHalflife = d.constFloat("wetnessHalflife", 600.0F);
		this.drynessHalflife = d.constFloat("drynessHalflife", 200.0F);
		for (Map.Entry<String, String> e : programs.properties.asMap().entrySet()) {
			String k = e.getKey();
			boolean uniform = k.startsWith("uniform.");
			if (!uniform && !k.startsWith("variable.")) {
				continue;
			}
			String[] parts = k.split("\\.", 3);
			if (parts.length < 3) {
				continue;
			}
			try {
				this.customs.add(new Object[] {parts[2], parts[1], PackExpression.parse(e.getValue()), uniform});
			} catch (IllegalArgumentException ex) {
				programs.log.add("custom uniform " + parts[2] + " not understood: " + ex.getMessage());
			}
		}
		this.context.scope = name -> this.values.get(name);
	}

	static Matrix4f purePerspective(final Matrix4f m) {
		org.joml.Vector4f r0 = m.getRow(0, new org.joml.Vector4f());
		org.joml.Vector4f r1 = m.getRow(1, new org.joml.Vector4f());
		org.joml.Vector4f r2 = m.getRow(2, new org.joml.Vector4f());
		org.joml.Vector4f r3 = m.getRow(3, new org.joml.Vector4f());
		float bx = -r3.x;
		float by = -r3.y;
		float bz = -r3.z;
		float tz = -r3.w;
		float a = (float) Math.sqrt(r0.x * r0.x + r0.y * r0.y + r0.z * r0.z);
		float b = (float) Math.sqrt(r1.x * r1.x + r1.y * r1.y + r1.z * r1.z);
		if (a < 1.0E-6F || b < 1.0E-6F || Math.abs(bx * bx + by * by + bz * bz - 1.0F) > 0.01F) {
			return new Matrix4f(m);
		}
		float c = r2.x * bx + r2.y * by + r2.z * bz;
		float d = r2.w - c * tz;
		Matrix4f p = new Matrix4f().zero();
		p.m00(a);
		p.m11(b);
		p.m22(c);
		p.m23(-1.0F);
		p.m32(d);
		return p;
	}

	private void put(final String name, final float... v) {
		this.values.put(name, v);
	}

	private void putMatrix(final String name, final Matrix4f m) {
		float[] a = new float[16];
		m.get(a);
		this.values.put(name, a);
	}

	private static float halflifeStep(final float current, final float target, final float halflifeTenths, final float dt) {
		float hl = Math.max(halflifeTenths * 0.1F, 0.001F);
		float k = (float) (1.0 - Math.pow(0.5, dt / hl));
		return current + (target - current) * k;
	}

	public void update(final Minecraft mc, final GameRenderState state, final Matrix4f projection, final int width, final int height) {
		long now = System.nanoTime();
		float dt = this.lastNanos == 0 ? 0.016F : (float) Math.min((now - this.lastNanos) / 1.0E9, 1.0);
		this.lastNanos = now;
		this.timeCounter = (this.timeCounter + dt) % 3600.0;
		this.frameCounter = (this.frameCounter + 1) % 720720;
		this.context.frameTime = dt;

		CameraRenderState camera = state.levelRenderState.cameraRenderState;
		SkyRenderState sky = state.levelRenderState.skyRenderState;
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		float partial = state.levelRenderState.worldPartialTicks;

		Matrix4f bobbed = new Matrix4f().scaling(1.0F, 1.0F, -1.0F).mul(projection);
		Matrix4f proj = purePerspective(bobbed);
		Matrix4f bob = new Matrix4f(proj).invert().mul(bobbed);
		Matrix4f modelView = new Matrix4f(bob).mul(camera.viewRotationMatrix);
		if (this.first) {
			this.prevModelView.set(modelView);
			this.prevProjection.set(proj);
		}
		putMatrix("gbufferModelView", modelView);
		putMatrix("gbufferModelViewInverse", new Matrix4f(modelView).invert());
		putMatrix("gbufferProjection", proj);
		putMatrix("gbufferProjectionInverse", new Matrix4f(proj).invert());
		putMatrix("iris_gbModelView", modelView);
		putMatrix("iris_gbModelViewInverse", new Matrix4f(modelView).invert());
		putMatrix("iris_gbProjection", proj);
		putMatrix("iris_gbProjectionInverse", new Matrix4f(proj).invert());
		float[] normal = new float[9];
		new org.joml.Matrix3f(modelView).get(normal);
		this.values.put("iris_gbNormalMatrix", normal);
		put("alphaTestRef", 0.1F);
		put("iris_AlphaTestRef", 0.1F);
		putMatrix("gbufferPreviousModelView", this.prevModelView);
		putMatrix("gbufferPreviousProjection", this.prevProjection);
		this.prevModelView.set(modelView);
		this.prevProjection.set(proj);

		float cx = (float) camera.pos.x;
		float cy = (float) camera.pos.y;
		float cz = (float) camera.pos.z;
		if (this.first) {
			this.prevCamera = new float[] {cx, cy, cz};
		}
		put("cameraPosition", cx, cy, cz);
		put("previousCameraPosition", this.prevCamera);
		put("cameraPositionInt", (float) Math.floor(camera.pos.x), (float) Math.floor(camera.pos.y), (float) Math.floor(camera.pos.z));
		put("cameraPositionFract", (float) (camera.pos.x - Math.floor(camera.pos.x)), (float) (camera.pos.y - Math.floor(camera.pos.y)),
			(float) (camera.pos.z - Math.floor(camera.pos.z)));
		put("previousCameraPositionInt", (float) Math.floor(this.prevCamera[0]), (float) Math.floor(this.prevCamera[1]), (float) Math.floor(this.prevCamera[2]));
		put("previousCameraPositionFract", this.prevCamera[0] - (float) Math.floor(this.prevCamera[0]),
			this.prevCamera[1] - (float) Math.floor(this.prevCamera[1]), this.prevCamera[2] - (float) Math.floor(this.prevCamera[2]));
		this.prevCamera = new float[] {cx, cy, cz};
		put("eyeAltitude", cy);

		boolean hasSun = sky.skybox == DimensionType.Skybox.OVERWORLD;
		float celestial = hasSun ? (float) (sky.sunAngle / (2.0 * Math.PI)) : 0.0F;
		celestial -= (float) Math.floor(celestial);
		float sunAngle = celestial < 0.75F ? celestial + 0.25F : celestial - 0.75F;
		float shadowAngle = sunAngle <= 0.5F ? sunAngle : sunAngle - 0.5F;
		put("sunAngle", sunAngle);
		put("shadowAngle", shadowAngle);
		Matrix4f celestialM = new Matrix4f(modelView).rotateY((float) Math.toRadians(-90.0))
			.rotateZ((float) Math.toRadians(this.sunPathRotation)).rotateX((float) (celestial * 2.0 * Math.PI));
		Vector4f sun = celestialM.transform(new Vector4f(0, 100, 0, 0));
		Vector4f moon = celestialM.transform(new Vector4f(0, -100, 0, 0));
		Vector4f up = new Matrix4f(modelView).transform(new Vector4f(0, 100, 0, 0));
		put("sunPosition", sun.x, sun.y, sun.z);
		put("moonPosition", moon.x, moon.y, moon.z);
		Vector4f light = sunAngle <= 0.5F ? sun : moon;
		put("shadowLightPosition", light.x, light.y, light.z);
		put("upPosition", up.x, up.y, up.z);

		float skyAngle = shadowAngle - 0.25F;
		if (skyAngle < 0) {
			skyAngle += 1.0F;
		}
		Matrix4f shadowMV = new Matrix4f().translate(0, 0, -100).rotateX((float) (Math.PI / 2))
			.rotateZ((float) (-skyAngle * 2.0 * Math.PI)).rotateX((float) Math.toRadians(this.sunPathRotation));
		float iv = this.shadowInterval;
		if (iv > 0) {
			shadowMV.translate(mod(cx, iv) - iv / 2, mod(cy, iv) - iv / 2, mod(cz, iv) - iv / 2);
		}
		Matrix4f shadowP = new Matrix4f().setOrthoSymmetric(this.shadowDistance * 2, this.shadowDistance * 2, 0.05F, 256.0F);
		putMatrix("shadowModelView", shadowMV);
		putMatrix("shadowModelViewInverse", new Matrix4f(shadowMV).invert());
		putMatrix("shadowProjection", shadowP);
		putMatrix("shadowProjectionInverse", new Matrix4f(shadowP).invert());
		putMatrix("iris_shModelView", shadowMV);
		putMatrix("iris_shModelViewInverse", new Matrix4f(shadowMV).invert());
		putMatrix("iris_shProjection", shadowP);
		putMatrix("iris_shProjectionInverse", new Matrix4f(shadowP).invert());
		float[] shadowNormal = new float[9];
		new org.joml.Matrix3f(shadowMV).get(shadowNormal);
		this.values.put("iris_shNormalMatrix", shadowNormal);

		long time = level == null ? 6000L : level.getDefaultClockTime();
		put("worldTime", (float) Math.floorMod(time, 24000L));
		put("worldDay", (float) Math.floorDiv(time, 24000L));
		put("moonPhase", (float) (sky.moonPhase == null ? 0 : sky.moonPhase.index()));
		put("frameCounter", (float) this.frameCounter);
		put("frameTime", dt);
		put("frameTimeCounter", (float) this.timeCounter);
		put("viewWidth", (float) width);
		put("viewHeight", (float) height);
		put("aspectRatio", (float) width / Math.max(1, height));
		float near = 0.05F;
		float far = mc.options.getEffectiveRenderDistance() * 16.0F;
		put("near", near);
		put("far", far);

		float rain = level == null ? 0.0F : level.getRainLevel(partial);
		float thunder = level == null ? 0.0F : level.getThunderLevel(partial);
		this.wetness = halflifeStep(this.wetness, rain, rain > this.wetness ? this.wetnessHalflife : this.drynessHalflife, dt);
		put("rainStrength", rain);
		put("thunderStrength", thunder);
		put("wetness", this.wetness);

		FogType fogType = camera.fogType;
		put("isEyeInWater", fogType == FogType.WATER ? 1.0F : fogType == FogType.LAVA ? 2.0F : fogType == FogType.POWDER_SNOW ? 3.0F : 0.0F);
		float skyLight = 15;
		float blockLight = 0;
		if (level != null) {
			BlockPos pos = camera.blockPos;
			skyLight = level.getBrightness(LightLayer.SKY, pos);
			blockLight = level.getBrightness(LightLayer.BLOCK, pos);
		}
		if (this.eyeSkySmooth < 0) {
			this.eyeSkySmooth = skyLight * 16;
			this.eyeBlockSmooth = blockLight * 16;
		}
		this.eyeSkySmooth = halflifeStep(this.eyeSkySmooth, skyLight * 16, this.eyeBrightnessHalflife, dt);
		this.eyeBlockSmooth = halflifeStep(this.eyeBlockSmooth, blockLight * 16, this.eyeBrightnessHalflife, dt);
		put("eyeBrightness", blockLight * 16, skyLight * 16);
		put("eyeBrightnessSmooth", Math.round(this.eyeBlockSmooth), Math.round(this.eyeSkySmooth));

		Vector4f fog = camera.fogData.color;
		put("fogColor", fog.x, fog.y, fog.z);
		Vector3fc skyColor = sky.skyColor;
		if (skyColor != null && hasSun) {
			put("skyColor", skyColor.x(), skyColor.y(), skyColor.z());
		} else {
			put("skyColor", fog.x, fog.y, fog.z);
		}
		put("fogStart", camera.fogData.renderDistanceStart);
		put("fogEnd", camera.fogData.renderDistanceEnd);
		put("fogDensity", 0.0F);
		put("fogMode", 9729.0F);
		put("fogShape", 1.0F);

		float nightVision = 0;
		float blindness = 0;
		float darkness = 0;
		if (player != null) {
			MobEffectInstance nv = player.getEffect(MobEffects.NIGHT_VISION);
			if (nv != null) {
				nightVision = nv.endsWithin(200) ? 0.7F + 0.3F * (float) Math.sin(nv.getDuration() * Math.PI * 0.2F) : 1.0F;
			}
			MobEffectInstance bl = player.getEffect(MobEffects.BLINDNESS);
			if (bl != null) {
				blindness = bl.getBlendFactor(player, partial);
			}
			MobEffectInstance dk = player.getEffect(MobEffects.DARKNESS);
			if (dk != null) {
				darkness = dk.getBlendFactor(player, partial);
			}
			put("is_on_ground", player.onGround() ? 1 : 0);
			put("is_sneaking", player.isShiftKeyDown() ? 1 : 0);
			put("is_sprinting", player.isSprinting() ? 1 : 0);
			put("is_burning", player.isOnFire() ? 1 : 0);
			put("is_invisible", player.isInvisible() ? 1 : 0);
			put("is_hurt", player.hurtTime > 0 ? 1 : 0);
			put("is_alive", player.isAlive() ? 1 : 0);
			put("is_in_water", player.isInWater() ? 1 : 0);
			put("currentPlayerHealth", player.getHealth() / Math.max(1.0F, player.getMaxHealth()));
			put("maxPlayerHealth", player.getMaxHealth());
			put("currentPlayerHunger", player.getFoodData().getFoodLevel() / 20.0F);
			put("currentPlayerAir", player.getAirSupply() / (float) Math.max(1, player.getMaxAirSupply()));
		}
		put("nightVision", nightVision);
		put("blindness", blindness);
		put("darknessFactor", darkness);
		put("darknessLightFactor", 0.0F);
		put("screenBrightness", mc.options.gamma().get().floatValue());
		put("hideGUI", state.guiRenderState.isHudHidden ? 1.0F : 0.0F);
		put("heldItemId", -1.0F);
		put("heldItemId2", -1.0F);
		put("heldBlockLightValue", 0.0F);
		put("heldBlockLightValue2", 0.0F);
		put("playerMood", 0.0F);
		put("endFlashIntensity", sky.skybox == DimensionType.Skybox.END ? sky.endFlashIntensity : 0.0F);
		put("endFlashPosition", 0.0F, 100.0F, 0.0F);
		if (level != null) {
			put("bedrockLevel", (float) level.getMinY());
			put("heightLimit", (float) level.getHeight());
			put("logicalHeightLimit", (float) level.getHeight());
			put("hasSkylight", level.dimensionType().hasSkyLight() ? 1.0F : 0.0F);
			put("hasCeiling", level.dimensionType().hasCeiling() ? 1.0F : 0.0F);
		}
		put("cloudHeight", state.levelRenderState.cloudHeight);
		float biomeId = -1.0F;
		float temperature = 0.8F;
		float precipitation = 0.0F;
		float rainfall = 0.4F;
		if (level != null) {
			try {
				net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> holder = level.getBiome(camera.blockPos);
				net.minecraft.world.level.biome.Biome biome = holder.value();
				biomeId = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME).getId(biome);
				temperature = biome.getBaseTemperature();
				precipitation = biome.getPrecipitationAt(camera.blockPos, level.getSeaLevel()).ordinal();
				rainfall = biome.hasPrecipitation() ? 0.5F : 0.0F;
			} catch (RuntimeException e) {
				biomeId = -1.0F;
			}
		}
		put("biome", biomeId);
		put("biome_category", -1.0F);
		put("biome_precipitation", precipitation);
		put("temperature", temperature);
		put("rainfall", rainfall);

		if (player != null) {
			net.minecraft.world.phys.Vec3 eye = player.getEyePosition(partial);
			net.minecraft.world.phys.Vec3 look = player.getViewVector(partial);
			put("eyePosition", (float) eye.x, (float) eye.y, (float) eye.z);
			put("relativeEyePosition", (float) (camera.pos.x - eye.x), (float) (camera.pos.y - eye.y), (float) (camera.pos.z - eye.z));
			put("playerLookVector", (float) look.x, (float) look.y, (float) look.z);
			put("isElytraFlying", player.isFallFlying() ? 1.0F : 0.0F);
		}
		put("lightningBoltPosition", 0.0F, 0.0F, 0.0F, 0.0F);
		put("entityColor", 0.0F, 0.0F, 0.0F, 0.0F);
		put("entityId", 0.0F);
		put("blockEntityId", 0.0F);
		put("currentRenderedItemId", 0.0F);
		put("renderStage", 0.0F);
		put("atlasSize", 0.0F, 0.0F);
		put("dhNearPlane", near);
		put("dhFarPlane", far);
		put("dhRenderDistance", far);
		putMatrix("dhProjection", proj);
		putMatrix("dhProjectionInverse", new Matrix4f(proj).invert());
		putMatrix("dhPreviousProjection", this.prevProjection);

		float target = 1.0F;
		HitResult hit = mc.hitResult;
		if (hit != null && hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult) {
			double dx = hit.getLocation().x - camera.pos.x;
			double dy = hit.getLocation().y - camera.pos.y;
			double dz = hit.getLocation().z - camera.pos.z;
			float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			Vector4f clip = new Matrix4f(proj).transform(new Vector4f(0, 0, -Math.max(dist, near), 1));
			target = Math.max(0.0F, Math.min(1.0F, clip.z / clip.w * 0.5F + 0.5F));
		}
		this.centerDepth = this.first ? target : halflifeStep(this.centerDepth, target, this.centerDepthHalflife, dt);
		put("centerDepthSmooth", this.centerDepth);

		Matrix4f identity = new Matrix4f();
		Matrix4f ortho = new Matrix4f(2, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, -1, -1, 0, 1);
		putMatrix("iris_ModelViewMatrix", identity);
		putMatrix("iris_ModelViewMatrixInverse", identity);
		putMatrix("iris_ProjectionMatrix", ortho);
		putMatrix("iris_ProjectionMatrixInverse", new Matrix4f(2, 0, 0, 0, 0, 2, 0, 0, 0, 0, 1, 0, -1, -1, 0, 1).invert());
		putMatrix("iris_TextureMatrix0", identity);
		putMatrix("iris_TextureMatrix1", new Matrix4f().scaling(1.0F / 256.0F).translate(8.0F, 8.0F, 8.0F));
		put("iris_NormalMatrix", 1, 0, 0, 0, 1, 0, 0, 0, 1);
		put("iris_FogColor", fog.x, fog.y, fog.z, fog.w);
		put("iris_FogStart", camera.fogData.renderDistanceStart);
		put("iris_FogEnd", camera.fogData.renderDistanceEnd);
		put("iris_FogDensity", 0.0F);
		put("iris_FogScale", 1.0F / Math.max(0.001F, camera.fogData.renderDistanceEnd - camera.fogData.renderDistanceStart));

		for (Object[] c : this.customs) {
			String name = (String) c[0];
			float[] v = ((PackExpression) c[2]).eval(this.context);
			String type = (String) c[1];
			if (type.equals("int")) {
				v = new float[] {(float) (int) v[0]};
			} else if (type.equals("bool")) {
				v = new float[] {v[0] != 0.0F ? 1.0F : 0.0F};
			}
			this.values.put(name, v);
		}
		this.first = false;
	}

	public static Map<String, String> biomeMacros(final ClientLevel level) {
		Map<String, String> macros = new HashMap<>();
		if (level == null) {
			return macros;
		}
		try {
			net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> registry =
				level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
			for (net.minecraft.resources.Identifier id : registry.keySet()) {
				net.minecraft.world.level.biome.Biome biome = registry.getValue(id);
				if (biome != null) {
					String name = id.getPath().toUpperCase(java.util.Locale.ROOT).replace('/', '_');
					macros.put("BIOME_" + name, Integer.toString(registry.getId(biome)));
				}
			}
		} catch (RuntimeException e) {
		}
		return macros;
	}

	private static float mod(final float a, final float b) {
		float r = a % b;
		return r < 0 ? r + b : r;
	}

	public void apply(final List<Object[]> uniforms) {
		for (Object[] u : uniforms) {
			float[] v = this.values.get((String) u[0]);
			if (v == null) {
				continue;
			}
			int loc = (Integer) u[1];
			int type = (Integer) u[2];
			switch (type) {
				case ShaderPackRuntime.T_FLOAT: GlApi.glUniform1f(loc, v[0]); break;
				case ShaderPackRuntime.T_VEC2: GlApi.glUniform2f(loc, v[0], at(v, 1)); break;
				case ShaderPackRuntime.T_VEC3: GlApi.glUniform3f(loc, v[0], at(v, 1), at(v, 2)); break;
				case ShaderPackRuntime.T_VEC4: GlApi.glUniform4f(loc, v[0], at(v, 1), at(v, 2), at(v, 3)); break;
				case ShaderPackRuntime.T_INT:
				case ShaderPackRuntime.T_BOOL:
				case ShaderPackRuntime.T_UINT:
					GlApi.glUniform1i(loc, (int) v[0]);
					break;
				case ShaderPackRuntime.T_IVEC2: GlApi.glUniform2i(loc, (int) v[0], (int) at(v, 1)); break;
				case ShaderPackRuntime.T_IVEC3: GlApi.glUniform3i(loc, (int) v[0], (int) at(v, 1), (int) at(v, 2)); break;
				case ShaderPackRuntime.T_IVEC4: GlApi.glUniform4i(loc, (int) v[0], (int) at(v, 1), (int) at(v, 2), (int) at(v, 3)); break;
				case ShaderPackRuntime.T_MAT4:
					if (v.length >= 16) {
						GlApi.glUniformMatrix4fv(loc, false, v);
					}
					break;
				case ShaderPackRuntime.T_MAT3:
					if (v.length >= 9) {
						GlApi.glUniformMatrix3fv(loc, false, v);
					}
					break;
				default:
					break;
			}
		}
	}

	private static float at(final float[] v, final int i) {
		return i < v.length ? v[i] : v[v.length - 1];
	}

	public void set(final String name, final float... v) {
		this.values.put(name, v);
	}

	public float[] value(final String name) {
		return this.values.get(name);
	}
}
