// Test-only bot that joins a localhost server, stands still and rejoins after a kick.
// usage: node bot.cjs <port> <name> [version]
const mc = require('minecraft-protocol');
const port = Number(process.argv[2]);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('localhost port required');
const name = process.argv[3] || 'Bot';
const version = process.argv[4] || '26.1';
const toServer = require('minecraft-data')(version).protocol.play.toServer.types;
const usesFlags = toServer.packet_position[1].some(field => field.name === 'flags');
let joins = 0;
function connect() {
  joins++;
  const client = mc.createClient({host: '127.0.0.1', port, username: name, version, auth: 'offline'});
  let position = null;
  const ground = usesFlags ? {flags: {onGround: true, hasHorizontalCollision: false}} : {onGround: true};
  const timer = setInterval(() => {
    if (!position) return;
    client.write('position', {x: position.x, y: position.y, z: position.z, ...ground});
    if (toServer.packet_tick_end) client.write('tick_end', {});
  }, 50);
  client.on('error', e => console.error(name, e.message));
  client.on('end', reason => {
    clearInterval(timer);
    console.log(name, 'disconnected', reason || '');
    if (joins < 5) setTimeout(connect, 8000); else process.exit(0);
  });
  client.on('login', () => console.log(name, 'joined'));
  client.on('update_health', p => { if (p.health <= 0) setTimeout(() => client.write('client_command', {actionId: 0}), 500); });
  client.on('position', packet => {
    const previous = position || {x: 0, y: 0, z: 0, yaw: 0, pitch: 0};
    position = {};
    for (const axis of ['x', 'y', 'z', 'yaw', 'pitch'])
      position[axis] = (packet.flags?.[axis] ? previous[axis] : 0) + packet[axis];
    if (packet.teleportId !== undefined) client.write('teleport_confirm', {teleportId: packet.teleportId});
    client.write('position_look', {...position, ...ground});
  });
}
connect();
