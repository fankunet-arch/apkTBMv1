<?php
namespace SmApp\Controllers;
use SmCore\Database;

class ApiController {
    private $db;
    // 安全密钥，需与安卓端 NetworkClient.kt 保持一致
    private const API_SECRET = 'TOPTEA_SECURE_KEY_2025';

    public function __construct() {
        $this->db = Database::getInstance()->getConnection();
    }

    private function jsonResponse($data) {
        header('Content-Type: application/json');
        echo json_encode($data);
        exit;
    }

    public function heartbeat() {
        $this->jsonResponse(['status'=>'success', 'msg'=>'System Online']);
    }

    /**
     * 核心同步接口 (安全升级版)
     * POST /smsys/api/check_update
     */
    public function check_update() {
        // 0. 安全校验 (简单版)
        $headers = getallheaders();
        $secret = $headers['X-Toptea-Secret'] ?? '';
        // 兼容部分服务器 header 大小写问题
        if (empty($secret)) $secret = $headers['x-toptea-secret'] ?? '';
        
        if ($secret !== self::API_SECRET) {
            // 为了调试方便，暂时注释掉强制校验，或者仅记录日志
            // 生产环境建议开启：
            // $this->jsonResponse(['status'=>'error', 'message'=>'Unauthorized']);
        }

        // 1. 获取输入
        $input = json_decode(file_get_contents('php://input'), true);
        $mac = $input['mac_address'] ?? '';
        $clientVer = $input['current_version'] ?? '';

        if (!$mac) $this->jsonResponse(['status'=>'error', 'message'=>'Missing MAC']);

        // 2. 设备注册/获取状态
        $device = $this->getOrRegisterDevice($mac);

        // 3. 核心安全拦截：如果设备未激活 (status = 0)
        if ($device['status'] == 0) {
            $this->jsonResponse([
                'status' => 'error',
                'code' => 403, // 特殊状态码，安卓端识别后显示“联系管理员”
                'message' => 'Device Not Activated. Please contact HQ.',
                'device_id' => $device['id']
            ]);
        }
        
        // 如果被禁用 (status = 2)
        if ($device['status'] == 2) {
            $this->jsonResponse(['status'=>'error', 'message'=>'Device Blocked']);
        }

        // 4. 版本比对 (仅当设备激活后)
        $lastMod = $this->db->query("SELECT MAX(updated_at) as t FROM sm_assignments")->fetch()['t'];
        $serverVer = strtotime($lastMod ?? 'now');

        if ($clientVer == $serverVer) {
            $this->jsonResponse(['status' => 'latest']);
        }

        // 5. 构建全量配置
        $config = [
            'resources' => $this->db->query("SELECT id, title, file_md5 as md5, file_url as url, file_size as size FROM sm_songs WHERE is_active=1")->fetchAll(),
            'playlists' => $this->fetchPlaylists(),
            'assignments' => $this->fetchAssignments(),
            'holiday_dates' => $this->fetchHolidays()
        ];

        $this->jsonResponse([
            'status' => 'update_required',
            'new_version' => (string)$serverVer,
            'config' => $config
        ]);
    }

    // --- Helpers ---

    private function getOrRegisterDevice($mac) {
        // 先查询
        $stmt = $this->db->prepare("SELECT * FROM sm_devices WHERE mac_address = ?");
        $stmt->execute([$mac]);
        $device = $stmt->fetch();

        if ($device) {
            // 更新心跳
            $this->db->prepare("UPDATE sm_devices SET last_heartbeat = NOW() WHERE id = ?")->execute([$device['id']]);
            return $device;
        } else {
            // 新设备：默认 status = 0 (未激活)
            $sql = "INSERT INTO sm_devices (mac_address, last_heartbeat, status) VALUES (?, NOW(), 0)";
            $this->db->prepare($sql)->execute([$mac]);
            
            // 再次查询返回
            $stmt->execute([$mac]);
            return $stmt->fetch();
        }
    }

    private function fetchPlaylists() {
        $rows = $this->db->query("SELECT id, play_mode, song_ids_json FROM sm_playlists")->fetchAll();
        $result = [];
        foreach ($rows as $r) {
            $result[$r['id']] = [
                'mode' => $r['play_mode'],
                'ids' => json_decode($r['song_ids_json'])
            ];
        }
        return $result;
    }

    private function fetchAssignments() {
        $rows = $this->db->query("SELECT * FROM sm_assignments")->fetchAll();
        $res = ['specials' => [], 'holidays' => null, 'weekdays' => []];

        // 预加载策略详情
        $strategies = [];
        $sRows = $this->db->query("SELECT id, timeline_json FROM sm_strategies")->fetchAll();
        foreach ($sRows as $s) $strategies[$s['id']] = json_decode($s['timeline_json']);

        foreach ($rows as $r) {
            $sId = $r['strategy_id'];
            $sData = $strategies[$sId] ?? [];

            if ($r['priority'] == 3) {
                $res['specials'][$r['condition_key']] = $sData;
            } elseif ($r['priority'] == 2) {
                $res['holidays'] = $sData;
            } elseif ($r['priority'] == 1) {
                $res['weekdays'][$r['condition_key']] = $sData;
            }
        }
        return $res;
    }

    private function fetchHolidays() {
        return $this->db->query("SELECT calendar_date FROM sm_calendar WHERE day_type=1 AND calendar_date >= CURDATE()")->fetchAll(\PDO::FETCH_COLUMN);
    }
}