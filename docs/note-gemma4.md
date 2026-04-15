# Report đánh giá Gemma 4:

## Bối cảnh sử dụng
- Đã test sử dụng mô hình gemma4 e2b (4B params) và gemma4 e4b (8B params) với framework [LiteRTLM](https://ai.google.dev/edge/litert-lm/overview?hl=vi)
- Framework này chỉ hỗ trợ chạy mô hình (inference) không hỗ trợ lưu context, conversation history, không có tool có sẵn... nên phải viết thêm services host quản lý context, conversation, tool, api để phục vụ cho việc test và đánh giá hiệu năng của mô hình khi chạy trên máy tính nhúng. Có nhiều framework khác nhau với tool khác nhau: Ollama, llama.cpp (2 tool ăn liền), litertlm...
- Hiện tại service lưu context, conversation history vào sqlite, tool registry hardcode (datetime, rogodocs, litertlmdocs) và API để gọi
- Hiện tại đang sử dụng 2 cách test: 
  - sử dụng litert-lm cli để prompt trực tiếp với model
  - viết thêm 1 service host api để test
- Model chỉ handle 1 conversation tại 1 thời điểm

## Các test đã thử:
- Test promp đơn giản (stateless): "Hello, what is gemma 4 model? And what the variants"
- Test promp sử dụng tool (stateless): "What day is today? Use datetime tool to check and answer", "What docs can you access in rogodocs?"
- Test promp đơn giản (có history): "Can you create a trip plan to Hanoi for 3 days and 2 nights?" -> "With the budget of 1000 per person, what would be the best option"
- Test promp sử dụng tool (có history): "What docs can you access in rogodocs?" -> "Can you describe the content in the ... docs?"
- Test promp xử lý context với ảnh: "Can you describe the content in this image?" -> (kèm theo 1 ảnh) -> "Can you describe the content in the image with more details?"
- Test promp với history dài: "Can you create a trip plan to Hanoi for 3 days and 2 nights?" -> "With the budget of 1000 per person, what would be the best option" -> "Can you draft the plan with the datetime?" (để test khả năng xử lý context dài và khả năng dùng tool)
- Note: Chưa sử dụng resoning mode của model

## Đánh giá phần cứng chạy gemma 4:
- Máy 1: Intel i5-12450H, 16GB RAM, WSL
- Máy 2: AMD Ryzen 5 5600G, 16GB RAM, Linux
- Máy 3: Nanopi R6C, Rockchip RK3588, 8GB RAM, Linux
- Máy 4: Nanopi R5C, Rockchip RK3568, 4GB RAM, Linux

### Tổng quan
- Với mô hình gemma4 e2b (4B params): 
  - Máy 1 và máy 2 đưa ra hiệu năng ổn, đối với conversation không có history, extra context, time to first token (TTFT) khoảng 4 - 5s, còn với conversation có history, extra context thì TTFT khoảng 10 - 15s. Thêm tool thì tăng lên 20 đến 30s tùy từng tool (đọc docs thường lâu hơn thì model xử lý như 1 chat). Có thể xử lý context khoảng 3000 chữ (~5000 token) ổn định, không crash, tăng context -> tăng ram sử dụng -> crash. RAM Usage khoảng 7GB khi chạy gemma4 e2b với context khoảng 3000 chữ.
  - Máy 3 với 8GB RAM thì tăng TTFT hơn máy 1 và máy 2 khoảng 50% (do memory được hàn vào mạch nên bandwidth ram cao hơn máy trên, tăng hiệu ng 1 chút), crash khi context lớn hơn 3000 chữ (~5000 token). RAM usage khoảng 7GB khi chạy gemma4 e2b với context khoảng 300 chữ, nếu tăng context lên nữa thì sẽ crash do hết RAM.
  - Máy 4 với 4GB RAM thì chậm hơn máy 1 và máy 2 khoảng 80% (TTFT khoảng 10 - 20s conversation đơn giản), crash khi context lớn hơn 200 chữ (~300 token). RAM usage khoảng 3.5GB khi chỉ sử dụng do phải load mô hinh vào RAM.
- Với môi hình gemma e4b (8B params):
  - Máy 1 và máy 2 TTFT khoảng 14 - 18s với conversation đơn giản, khoảng 20 - 24s với conversation có history, extra context, thêm tool thì tăng lên 30 - 40s tùy từng tool. RAM usage khoảng 12GB khi chạy gemma4 e4b với context khoảng 3000 chữ, nếu tăng context lên nữa thì sẽ crash do hết RAM. Sử dụng thấy thường xuyên crash hơn so với mô hình e2b, có thể do mô hình lớn hơn nên sử dụng RAM nhiều hơn, nếu RAM không đủ thì sẽ crash.
  - Máy 3 với 8GB RAM thì tăng thêm 80% TTFT, crash khi đọc docs context lớn hơn 3000 chữ (~5000 token).
  - Máy 4 không chạy được

### Kết luận: 
  - Máy nhiều ram hơn thì sẽ sử dụng được mô hình lớn hơn, xử lý được context lớn hơn, memory bandwidth cao hơn sẽ giúp tăng hiệu năng (lý do benchmark trên máy MAC cao hơn so với máy chạy CPU khác, noGPU ), tuy nhiên nếu RAM không đủ thì sẽ crash, nên cần cân nhắc giữa hiệu năng và chi phí phần cứng khi lựa chọn mô hình để chạy trên máy tính nhúng. Mặc dù quảng cáo context window 125k token nhưng thực tế sẽ crash khi load khoảng 40k token
  - Máy 1 crash nhiều hơn vì inference trên WSL thay vì Native Linux
  - Mô hình cũng có mức sử dụng khác nhau đối với framework khác nhau. Nhưng hiện tại framework LiteRTLM tối ưu về RAM nhất (đánh giá chủ quan)

## Ứng dụng

### Bài toán đối (sử dụng trello, github, jira, setup RAG assistant) cho nhóm nhỏ hoặc hộ gia đình:
- Với mô hình gemma4 e2b (4B params), Cấu hình recommend là máy có RAM 16 GB đối với máy linux hoặc 8GB RAM máy có CPU có memory bandwidth cao (ví dụ như máy MAC) và có GPU sẽ nhanh hơn đáng kể (đánh giá theo review benchmark, chưa thử) (theo benchmark của google [link](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)) tuy nhiên mô hình e2b sử dụng tool không ngon bằng mô hình e4b. Vẫn ưu tiên sử dụng MAC nhất vì rẻ và hiệu năng tốt nhất, có model và framework tối ưu cho MAC (gemma 4 e2b it MLX).
- Với mô hình gemma4 e4b (8B params), cấu hình recommend là máy có RAM 24 - 32 GB đối với máy linux hoặc 16GB RAM đối với MAC chip M (apple silicons). Nếu sử dụng GPU thì cũng cần có lượng VRAM tương ứng.
- Có thể tích hợp MCP với gemma4 nhưng phải sử dụng lib python để gọi MCP (hiện tại chưa hỗ trợ cho windows, java chưa test với mcp sdk nhưng khả năng sẽ phải viết interface). Vẫn ưu tiên sử dụng MAC nhất.
- Tích hợp github, jira, trello thì sẽ phải viết tool riêng để gọi API của từng tool, nếu cùng hệ thống (ví dụ như hệ Atlassian) thì có thể tái sử dụng code cho các tool còn lại, tuy nhiên sẽ tốn công để viết tool cho từng hệ thống khác nhau (ví dụ như Microsoft Planner, Google Calendar...)
- Hiện tại mới chỉ test các tool đơn giản như đọc tài liệu từ folder, nhưng 2 môn hình đều không có vấn đề gì khi được instruct rõ ràng, instruct không rõ ràng thì model sẽ không biết gọi tool gì. Ngoài ra phần description cho tool cũng phải rõ ràng, nhưng cân bằng đủ lượng token model phải xử lý

### Bài toán normalize data trong data pipeline (cá nhân) (đã test với khoảng trên 2000 tên sản phẩm)
- Sử dụng mô hình gemma4 e2b cấu hình máy 2, sử dụng với việc normalize tên các sản phẩm công nghệ để đưa các sản phẩm về 1 format chung với output json. Kết quả tương đối hài lòng (tốn khoảng 1p để sử lý batch 10 tên sản phẩm) không sử dụng context.
- Mức độ chính xác vào khoảng 40% đối với data raw (lấy từ các website bán hàng) và 60% khi đã làm sạch

## Sử dụng tool
- Do việc sử dụng JVM với SDK thì việc tích hợp MCP sẽ phức tạp hơn nhiều so với sử dụng lib python. Tuy nhiên sử dụng JVM vì muốn có khả năng inference song song nhiều conversation (đã thử), để có thể làm được bài toán automatic agents. (hoặc agents spawn agents)
- Nếu sử dụng lib python thì sẽ dễ dàng tích hợp MCP hơn, tuy nhiên tầng service sẽ gặp khó khăn trong việc sử lý nhiều conversation, vì python không có native multi-threading. (từng agents làm 1 việc 1)
- Đánh giá dựa trên framework litertlm thì việc tích hợp tool còn hạn chế vì framework còn mới, chưa có nhiều support như sử dụng llama.cpp hoặc ollama.